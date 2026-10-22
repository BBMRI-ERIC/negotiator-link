package eu.bbmri_eric.negotiator.negotiatorlink.integration.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.job.OutboxClaimReaper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.EventSource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxPoller;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxClaimer;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxPoller;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.integration.IntegrationBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class OutboxClaimerIT extends IntegrationBase {

  @Autowired private OutboxClaimer claimer;
  @Autowired private OutboxIntentRepository outboxIntentRepository;
  @Autowired private InboxEventRepository inboxEventRepository;

  @MockitoBean private OutboxPoller outboxPoller;
  @MockitoBean private InboxPoller inboxPoller;
  @MockitoBean private OutboxClaimReaper outboxClaimReaper;

  @Test
  void claim_duePendingRows_flipsToInProgressWithClaimExpiryAndToken() {
    OutboxIntent due = save(pending(Instant.now().minusSeconds(5)));
    OutboxIntent notDue = save(pending(Instant.now().plusSeconds(300)));

    Map<Long, UUID> claimed = claimer.claim(10, Duration.ofSeconds(60));

    assertThat(claimed).containsOnlyKeys(due.getRowId());
    UUID token = claimed.get(due.getRowId());
    assertThat(token).isNotNull();

    OutboxIntent reloadedDue = outboxIntentRepository.findById(due.getRowId()).orElseThrow();
    assertThat(reloadedDue.getStatus()).isEqualTo(OutboxStatus.IN_PROGRESS);
    assertThat(reloadedDue.getClaimExpiresAt()).isNotNull();
    assertThat(reloadedDue.getClaimToken()).isEqualTo(token);

    OutboxIntent reloadedNotDue = outboxIntentRepository.findById(notDue.getRowId()).orElseThrow();
    assertThat(reloadedNotDue.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(reloadedNotDue.getClaimToken()).isNull();
  }

  @Test
  void claim_respectsBatchLimitAndRowOrder() {
    OutboxIntent first = save(pending(Instant.now().minusSeconds(5)));
    save(pending(Instant.now().minusSeconds(5)));

    Map<Long, UUID> claimed = claimer.claim(1, Duration.ofSeconds(60));

    assertThat(claimed).containsOnlyKeys(first.getRowId());
  }

  @Test
  void claim_emptyWhenNothingToBeProcessed() {
    save(pending(Instant.now().plusSeconds(300)));

    assertThat(claimer.claim(10, Duration.ofSeconds(60))).isEmpty();
  }

  @Test
  void activateClaim_matchingToken_updatesExpiryAndReturnsTrue() {
    OutboxIntent intent = save(pending(Instant.now()));
    Map<Long, UUID> claimed = claimer.claim(10, Duration.ofSeconds(30));
    UUID token = claimed.get(intent.getRowId());

    boolean activated = claimer.activateClaim(intent.getRowId(), token, Duration.ofSeconds(60));

    assertThat(activated).isTrue();
    OutboxIntent reloaded = outboxIntentRepository.findById(intent.getRowId()).orElseThrow();
    assertThat(reloaded.getClaimExpiresAt()).isAfter(Instant.now().plusSeconds(50));
  }

  @Test
  void activateClaim_wrongToken_doesNotExtendAndReturnsFalse() {
    OutboxIntent intent = save(pending(Instant.now()));
    claimer.claim(10, Duration.ofSeconds(30));
    Instant expiryBefore =
        outboxIntentRepository.findById(intent.getRowId()).orElseThrow().getClaimExpiresAt();

    boolean activated =
        claimer.activateClaim(intent.getRowId(), UUID.randomUUID(), Duration.ofSeconds(60));

    assertThat(activated).isFalse();
    OutboxIntent reloaded = outboxIntentRepository.findById(intent.getRowId()).orElseThrow();
    assertThat(reloaded.getClaimExpiresAt()).isEqualTo(expiryBefore);
  }

  @Test
  void reclaimExpiredClaims_returnsOnlyExpiredInProgressRowsToPendingAndClearsToken() {
    OutboxIntent expired = save(inProgress(Instant.now().minusSeconds(10)));
    OutboxIntent live = save(inProgress(Instant.now().plusSeconds(60)));

    List<Long> reclaimed = claimer.reclaimExpiredClaims();

    assertThat(reclaimed).containsExactly(expired.getRowId());
    OutboxIntent reloadedExpired =
        outboxIntentRepository.findById(expired.getRowId()).orElseThrow();
    assertThat(reloadedExpired.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(reloadedExpired.getClaimExpiresAt()).isNull();
    assertThat(reloadedExpired.getClaimToken()).isNull();
    OutboxIntent reloadedLive = outboxIntentRepository.findById(live.getRowId()).orElseThrow();
    assertThat(reloadedLive.getStatus()).isEqualTo(OutboxStatus.IN_PROGRESS);
  }

  private OutboxIntent save(OutboxIntent intent) {
    return outboxIntentRepository.save(intent);
  }

  private OutboxIntent pending(Instant nextAttemptAt) {
    OutboxIntent intent =
        new OutboxIntent(
            newInboxRowId(),
            OutboxIntentType.ADD_COMMENT,
            TargetKind.TICKET.name(),
            "ticket-1",
            "neg-1",
            "bbmri:DE:MHH",
            "{}");
    intent.setNextAttemptAt(nextAttemptAt);
    return intent;
  }

  private Long newInboxRowId() {
    InboxEvent event =
        new InboxEvent(EventSource.NEGOTIATOR.name(), UUID.randomUUID().toString(), "TEST", "{}");
    return inboxEventRepository.save(event).getRowId();
  }

  private OutboxIntent inProgress(Instant claimExpiresAt) {
    OutboxIntent intent = pending(Instant.now());
    intent.setStatus(OutboxStatus.IN_PROGRESS);
    intent.setClaimExpiresAt(claimExpiresAt);
    intent.setClaimToken(UUID.randomUUID());
    return intent;
  }
}
