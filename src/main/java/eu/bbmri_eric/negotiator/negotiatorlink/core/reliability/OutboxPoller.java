package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the outbox and routes each intent to a per-negotiation worker thread. All intents for the
 * same negotiation are processed by the same worker (hash(negotiationId) % N), preserving ordering
 * within a negotiation while allowing parallel processing across negotiations.
 */
@Slf4j
@Component
public class OutboxPoller {

  private final OutboxClaimer claimer;
  private final OutboxDeliverer deliverer;
  private final OutboxIntentRepository outboxIntentRepository;
  private final int batchSize;
  private final Duration claimTimeout;
  private final ExecutorService[] workers;

  public OutboxPoller(
      OutboxClaimer claimer,
      OutboxDeliverer deliverer,
      OutboxIntentRepository outboxIntentRepository,
      NegotiatorLinkProperties properties) {
    this.claimer = claimer;
    this.deliverer = deliverer;
    this.outboxIntentRepository = outboxIntentRepository;
    NegotiatorLinkProperties.OutboxProperties outbox = properties.getOutbox();
    this.batchSize = outbox.getBatchSize();
    this.claimTimeout = outbox.getClaimTimeout();

    int workerCount = outbox.getWorkerCount();
    this.workers = new ExecutorService[workerCount];
    for (int i = 0; i < workerCount; i++) {
      final int idx = i;
      @SuppressWarnings("resource") // lifecycle is managed by @PreDestroy
      ExecutorService worker =
          Executors.newSingleThreadExecutor(r -> new Thread(r, "outbox-worker-" + idx));
      workers[i] = worker;
    }
  }

  @Scheduled(fixedDelayString = "${negotiator-link.outbox.poll-delay-ms:500}")
  public void poll() {
    while (!deliverBatch()) {
      // drain until empty
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
   * Claims one batch, routes each intent to its negotiation's worker, and returns {@code true} when
   * the batch was empty (nothing left to claim right now).
   */
  @SuppressWarnings("resource")
  private boolean deliverBatch() {
    List<Long> rowIds = claimer.claim(batchSize, claimTimeout);
    if (rowIds.isEmpty()) {
      return true;
    }
    Map<Long, String> negotiationIdByRowId = loadNegotiationIds(rowIds);
    for (Long rowId : rowIds) {
      String negotiationId = negotiationIdByRowId.getOrDefault(rowId, rowId.toString());
      routingWorker(negotiationId).submit(() -> deliverOne(rowId));
    }
    return false;
  }

  private Map<Long, String> loadNegotiationIds(List<Long> rowIds) {
    List<Object[]> pairs = outboxIntentRepository.findRowIdAndNegotiationIdByRowIdIn(rowIds);
    Map<Long, String> result = new HashMap<>(pairs.size());
    for (Object[] pair : pairs) {
      result.put(((Number) pair[0]).longValue(), (String) pair[1]);
    }
    return result;
  }

  private ExecutorService routingWorker(String negotiationId) {
    return workers[Math.abs(negotiationId.hashCode()) % workers.length];
  }

  private void deliverOne(Long rowId) {
    try {
      DispatchResult result = deliverer.dispatch(rowId);
      deliverer.commit(rowId, result);
    } catch (Exception e) {
      log.error("Outbox delivery failed for intent {} — recording failure", rowId, e);
      deliverer.recordFailure(rowId, e);
    }
  }
}
