package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import eu.bbmri_eric.negotiator.negotiatorlink.core.job.StaleRecordsCleanupJob;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaleRecordsCleanupJobTest {

  @Mock private OriginatedPostRepository originatedPostRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;
  @Mock private InboxEventRepository inboxEventRepository;

  @Test
  void deleteStaleRows_whenCalled_deletesBothRepositoriesWithCutoff() {
    var job =
        new StaleRecordsCleanupJob(
            originatedPostRepository,
            originatedTransitionRepository,
            inboxEventRepository,
            Duration.ofHours(24));
    job.deleteStaleRows();
    verify(originatedPostRepository).deleteByCreatedAtBefore(any(Instant.class));
    verify(originatedTransitionRepository).deleteByCreatedAtBefore(any(Instant.class));
    verify(inboxEventRepository)
        .deleteByStatusInAndReceivedAtBefore(
            eq(List.of(InboxStatus.PROCESSED, InboxStatus.SKIPPED)), any(Instant.class));
  }
}
