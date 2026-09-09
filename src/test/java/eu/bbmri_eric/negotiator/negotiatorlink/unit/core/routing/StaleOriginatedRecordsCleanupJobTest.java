package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import eu.bbmri_eric.negotiator.negotiatorlink.core.job.StaleOriginatedRecordsCleanupJob;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaleOriginatedRecordsCleanupJobTest {

  @Mock private OriginatedPostRepository originatedPostRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;

  @Test
  void deleteStaleRows_whenCalled_deletesBothRepositoriesWithCutoff() {
    var job =
        new StaleOriginatedRecordsCleanupJob(
            originatedPostRepository, originatedTransitionRepository, Duration.ofHours(24));
    job.deleteStaleRows();
    verify(originatedPostRepository).deleteByCreatedAtBefore(any(Instant.class));
    verify(originatedTransitionRepository).deleteByCreatedAtBefore(any(Instant.class));
  }
}
