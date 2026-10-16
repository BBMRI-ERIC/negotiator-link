package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the outbox and routes each intent to a per-negotiation worker thread. All intents for the
 * same negotiation are processed by the same worker, preserving ordering within a negotiation while
 * allowing parallel processing across negotiations.
 *
 * <p>Outstanding work is bounded to {@code workerCount * queueDepth} by a global in-flight
 * semaphore: the poller claims only as many rows as there are free permits and acquires a permit
 * before each submit, so it never enqueues more than the workers can hold. This caps memory and
 * keeps a claimed row from waiting in a worker queue past its lease — which would let {@code
 * OutboxClaimReaper} release it and have the next poll re-claim and re-submit it in a tight loop.
 */
@Slf4j
@Component
public class OutboxPoller {

  private final OutboxClaimer claimer;
  private final OutboxDeliverer deliverer;
  private final OutboxIntentRepository outboxIntentRepository;
  private final Duration claimTimeout;
  private final ExecutorService[] workers;
  private final Semaphore inFlight;

  public OutboxPoller(
      OutboxClaimer claimer,
      OutboxDeliverer deliverer,
      OutboxIntentRepository outboxIntentRepository,
      NegotiatorLinkProperties properties) {
    this.claimer = claimer;
    this.deliverer = deliverer;
    this.outboxIntentRepository = outboxIntentRepository;
    NegotiatorLinkProperties.OutboxProperties outboxProperties = properties.getOutbox();
    this.claimTimeout = outboxProperties.getClaimTimeout();

    int workerCount = outboxProperties.getWorkerCount();
    this.workers = new ExecutorService[workerCount];
    for (int i = 0; i < workerCount; i++) {
      final int idx = i;
      @SuppressWarnings("resource") // lifecycle is managed by @PreDestroy
      ExecutorService worker =
          Executors.newSingleThreadExecutor(r -> new Thread(r, "outbox-worker-" + idx));
      workers[i] = worker;
    }
    this.inFlight = new Semaphore(workerCount * outboxProperties.getQueueDepth());
  }

  @Scheduled(fixedDelayString = "${negotiator-link.outbox.poll-delay-ms:500}")
  public void poll() {
    while (!deliverBatch()) {
      // process until empty
    }
  }

  @PreDestroy
  public void shutdown() {
    for (ExecutorService worker : workers) {
      worker.shutdown();
    }
    long timeoutSeconds = claimTimeout.toSeconds();
    for (ExecutorService worker : workers) {
      try {
        if (!worker.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
          log.warn("Outbox worker did not terminate within {}s — forcing shutdown", timeoutSeconds);
          worker.shutdownNow();
        }
      } catch (InterruptedException e) {
        worker.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Claims at most as many rows as there are free in-flight permits, routes each to its
   * negotiation's worker, and returns {@code true} when no free capacity or an empty table.
   */
  @SuppressWarnings("resource")
  private boolean deliverBatch() {
    int free = inFlight.availablePermits();
    if (free == 0) {
      return true;
    }
    Map<Long, UUID> claimed = claimer.claim(free, claimTimeout);
    if (claimed.isEmpty()) {
      return true;
    }
    Map<Long, String> negotiationIdByRowId = loadNegotiationIds(claimed.keySet());
    for (Map.Entry<Long, UUID> entry : claimed.entrySet()) {
      Long rowId = entry.getKey();
      UUID token = entry.getValue();
      String negotiationId = negotiationIdByRowId.getOrDefault(rowId, rowId.toString());
      inFlight.acquireUninterruptibly();
      boolean submitted = false;
      try {
        routingWorker(negotiationId)
            .submit(
                () -> {
                  try {
                    deliverOne(rowId, token);
                  } finally {
                    inFlight.release();
                  }
                });
        submitted = true;
      } finally {
        if (!submitted) {
          inFlight.release();
        }
      }
    }
    return false;
  }

  private Map<Long, String> loadNegotiationIds(Set<Long> rowIds) {
    List<Object[]> pairs = outboxIntentRepository.findRowIdAndNegotiationIdByRowIdIn(rowIds);
    Map<Long, String> result = new HashMap<>(pairs.size());
    for (Object[] pair : pairs) {
      result.put(((Number) pair[0]).longValue(), (String) pair[1]);
    }
    return result;
  }

  private ExecutorService routingWorker(String negotiationId) {
    return workers[Math.floorMod(negotiationId.hashCode(), workers.length)];
  }

  private void deliverOne(Long rowId, UUID claimToken) {
    boolean stillOwned = claimer.activateClaim(rowId, claimToken, claimTimeout);
    if (!stillOwned) {
      log.warn("Intent {} was reclaimed before worker started processing — skipping", rowId);
      return;
    }
    try {
      DispatchResult result = deliverer.dispatch(rowId, claimToken);
      deliverer.commit(rowId, claimToken, result);
    } catch (Exception e) {
      log.error("Outbox delivery failed for intent {} — recording failure", rowId, e);
      deliverer.recordFailure(rowId, claimToken, e);
    }
  }
}
