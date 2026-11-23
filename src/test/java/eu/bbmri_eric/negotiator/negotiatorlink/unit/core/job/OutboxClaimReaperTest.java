package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.job;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.job.OutboxClaimReaper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxClaimer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxClaimReaperTest {

  @Mock private OutboxClaimer claimer;

  @InjectMocks private OutboxClaimReaper reaper;

  @Test
  void reclaimExpiredClaims_rowsReclaimed_doesNotFail() {
    when(claimer.reclaimExpiredClaims()).thenReturn(List.of(1L, 2L, 3L));

    reaper.reclaimExpiredClaims();

    verify(claimer).reclaimExpiredClaims();
  }

  @Test
  void reclaimExpiredClaims_nothingReclaimed_doesNotFail() {
    when(claimer.reclaimExpiredClaims()).thenReturn(List.of());

    reaper.reclaimExpiredClaims();

    verify(claimer).reclaimExpiredClaims();
    verifyNoMoreInteractions(claimer);
  }
}
