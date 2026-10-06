package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.job.OutboxClaimReaper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxPoller;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxClaimer;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxPoller;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class OutboxClaimerTest {

  @Autowired private OutboxClaimer claimer;
  @Autowired private OutboxIntentRepository outboxIntentRepository;

  @MockitoBean private OutboxPoller outboxPoller;
  @MockitoBean private InboxPoller inboxPoller;
  @MockitoBean private OutboxClaimReaper outboxClaimReaper;

  @BeforeEach
  void clear() {
    outboxIntentRepository.deleteAll();
  }

  @Test
  void claim_duePendingRows_flipsToInProgressWithClaimExpiry() {
    OutboxIntent due = save(pending(1L, Instant.now().minusSeconds(5)));
    OutboxIntent notDue = save(pending(2L, Instant.now().plusSeconds(300)));

    List<Long> claimed = claimer.claim(10, Duration.ofSeconds(60));

    assertThat(claimed).containsExactly(due.getRowId());
    OutboxIntent reloadedDue = outboxIntentRepository.findById(due.getRowId()).orElseThrow();
    assertThat(reloadedDue.getStatus()).isEqualTo(OutboxStatus.IN_PROGRESS);
    assertThat(reloadedDue.getClaimExpiresAt()).isNotNull();
    OutboxIntent reloadedNotDue = outboxIntentRepository.findById(notDue.getRowId()).orElseThrow();
    assertThat(reloadedNotDue.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(reloadedNotDue.getClaimExpiresAt()).isNull();
  }

  @Test
  void claim_respectsBatchLimitAndRowOrder() {
    OutboxIntent first = save(pending(1L, Instant.now().minusSeconds(5)));
    save(pending(2L, Instant.now().minusSeconds(5)));

    List<Long> claimed = claimer.claim(1, Duration.ofSeconds(60));

    assertThat(claimed).containsExactly(first.getRowId());
  }

  @Test
  void claim_emptyWhenNothingToBeProcessed() {
    save(pending(1L, Instant.now().plusSeconds(300)));

    assertThat(claimer.claim(10, Duration.ofSeconds(60))).isEmpty();
  }

  @Test
  void reclaimExpiredClaims_returnsOnlyExpiredInProgressRowsToPending() {
    OutboxIntent expired = save(inProgress(1L, Instant.now().minusSeconds(10)));
    OutboxIntent live = save(inProgress(2L, Instant.now().plusSeconds(60)));

    List<Long> reclaimed = claimer.reclaimExpiredClaims();

    assertThat(reclaimed).containsExactly(expired.getRowId());
    OutboxIntent reloadedExpired =
        outboxIntentRepository.findById(expired.getRowId()).orElseThrow();
    assertThat(reloadedExpired.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(reloadedExpired.getClaimExpiresAt()).isNull();
    OutboxIntent reloadedLive = outboxIntentRepository.findById(live.getRowId()).orElseThrow();
    assertThat(reloadedLive.getStatus()).isEqualTo(OutboxStatus.IN_PROGRESS);
  }

  private OutboxIntent save(OutboxIntent intent) {
    return outboxIntentRepository.save(intent);
  }

  private OutboxIntent pending(Long inboxRowId, Instant nextAttemptAt) {
    OutboxIntent intent =
        new OutboxIntent(
            inboxRowId,
            OutboxIntentType.ADD_COMMENT,
            TargetKind.TICKET.name(),
            "ticket-1",
            "neg-1",
            "bbmri:DE:MHH",
            "{}");
    intent.setNextAttemptAt(nextAttemptAt);
    return intent;
  }

  private OutboxIntent inProgress(Long inboxRowId, Instant claimExpiresAt) {
    OutboxIntent intent = pending(inboxRowId, Instant.now());
    intent.setStatus(OutboxStatus.IN_PROGRESS);
    intent.setClaimExpiresAt(claimExpiresAt);
    return intent;
  }
}
