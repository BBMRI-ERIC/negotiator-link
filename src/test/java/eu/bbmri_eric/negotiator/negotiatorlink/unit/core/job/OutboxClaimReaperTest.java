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
  void run_releasesAllClaims() {
    when(claimer.releaseAllClaims()).thenReturn(List.of(1L, 2L));

    reaper.run(null);

    verify(claimer).releaseAllClaims();
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
