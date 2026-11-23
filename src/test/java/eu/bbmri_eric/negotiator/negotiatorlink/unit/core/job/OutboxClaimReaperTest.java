package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.job.OutboxClaimReaper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxClaimer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
class OutboxClaimReaperTest {

  private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(30);
  private static final long STARTUP_EPOCH_MILLI = 1_700_000_000_000L;

  @Mock private OutboxClaimer claimer;
  @Mock private NegotiatorLinkProperties properties;
  @Mock private ApplicationContext applicationContext;

  private OutboxClaimReaper reaper;

  @BeforeEach
  void setUp() {
    NegotiatorLinkProperties.OutboxProperties outbox =
        new NegotiatorLinkProperties.OutboxProperties();
    outbox.setClaimTimeout(CLAIM_TIMEOUT);
    when(properties.getOutbox()).thenReturn(outbox);
    when(applicationContext.getStartupDate()).thenReturn(STARTUP_EPOCH_MILLI);

    reaper = new OutboxClaimReaper(claimer, properties, applicationContext);
  }

  @Test
  void run_reclaimsClaimsTakenBeforeStartup() {
    when(claimer.reclaimClaimsStartedBefore(any(), eq(CLAIM_TIMEOUT))).thenReturn(List.of(1L, 2L));

    reaper.run(null);

    verify(claimer)
        .reclaimClaimsStartedBefore(
            eq(Instant.ofEpochMilli(STARTUP_EPOCH_MILLI)), eq(CLAIM_TIMEOUT));
    verifyNoMoreInteractions(claimer);
  }

  @Test
  void releaseExpiredClaims_rowsReclaimed_doesNotFail() {
    when(claimer.releaseExpiredClaims()).thenReturn(List.of(1L, 2L, 3L));

    reaper.releaseExpiredClaims();

    verify(claimer).releaseExpiredClaims();
  }

  @Test
  void releaseExpiredClaims_nothingReclaimed_doesNotFail() {
    when(claimer.releaseExpiredClaims()).thenReturn(List.of());

    reaper.releaseExpiredClaims();

    verify(claimer).releaseExpiredClaims();
    verifyNoMoreInteractions(claimer);
  }
}
