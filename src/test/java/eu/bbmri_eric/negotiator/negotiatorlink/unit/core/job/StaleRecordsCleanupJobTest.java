package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import eu.bbmri_eric.negotiator.negotiatorlink.core.job.StaleRecordsCleanupJob;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
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
  @Mock private InboxEventRepository inboxEventRepository;
  @Mock private OutboxIntentRepository outboxIntentRepository;

  @Test
  void deleteStaleRows_whenCalled_deletesAllRepositoriesWithCutoff() {
    var job =
        new StaleRecordsCleanupJob(
            originatedPostRepository,
            inboxEventRepository,
            outboxIntentRepository,
            Duration.ofHours(24));
    job.deleteStaleRows();
    verify(originatedPostRepository)
        .deleteTerminalAndOrphanedByCreatedAtBefore(
            any(Instant.class), eq(OutboxStatus.NONTERMINAL));
    verify(outboxIntentRepository)
        .deleteByStatusInAndCreatedAtBefore(
            eq(List.of(OutboxStatus.DELIVERED, OutboxStatus.FAILED_PERMANENT)), any(Instant.class));
    verify(inboxEventRepository)
        .deleteOrphanedByStatusInAndReceivedAtBefore(
            eq(List.of(InboxStatus.PROCESSED.name(), InboxStatus.SKIPPED.name())),
            any(Instant.class));
  }
}
