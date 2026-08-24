package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import eu.bbmri_eric.negotiator.negotiatorlink.core.job.DeduplicationCleanupJob;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeduplicationCleanupJobTest {

  @Mock private OriginatedPostRepository originatedPostRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;
  @InjectMocks private DeduplicationCleanupJob job;

  @Test
  void deleteStaleRows_whenCalled_deletesBothRepositoriesWithCutoff() {
    job.deleteStaleRows();
    verify(originatedPostRepository).deleteByCreatedAtBefore(any(Instant.class));
    verify(originatedTransitionRepository).deleteByCreatedAtBefore(any(Instant.class));
  }
}
