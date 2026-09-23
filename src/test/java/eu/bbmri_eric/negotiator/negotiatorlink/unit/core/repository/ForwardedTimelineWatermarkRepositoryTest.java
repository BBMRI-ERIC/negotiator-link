package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.JpaConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermark;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermarkId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.ForwardedTimelineWatermarkRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(JpaConfig.class)
class ForwardedTimelineWatermarkRepositoryTest {

  private static final LocalDateTime TIMESTAMP = LocalDateTime.parse("2024-01-03T11:00:00");

  @Autowired private ForwardedTimelineWatermarkRepository repository;

  @Test
  void findByIdForUpdate_whenRowExists_returnsPersistedRow() {
    ForwardedTimelineWatermarkId id = new ForwardedTimelineWatermarkId("neg-1", "ticket-1");
    repository.saveAndFlush(new ForwardedTimelineWatermark(id, TIMESTAMP, 2));

    Optional<ForwardedTimelineWatermark> found = repository.findByIdForUpdate(id);

    assertThat(found).isPresent();
    assertThat(found.get().getLastTimestamp()).isEqualTo(TIMESTAMP);
    assertThat(found.get().getLastTimestampCount()).isEqualTo(2);
    assertThat(found.get().getUpdatedAt()).isNotNull();
  }

  @Test
  void findByIdForUpdate_whenRowAbsent_returnsEmpty() {
    Optional<ForwardedTimelineWatermark> found =
        repository.findByIdForUpdate(new ForwardedTimelineWatermarkId("neg-x", "ticket-x"));

    assertThat(found).isEmpty();
  }

  @Test
  void save_whenDistinctNegotiationAndTicket_rowsAreIndependent() {
    repository.saveAndFlush(
        new ForwardedTimelineWatermark(
            new ForwardedTimelineWatermarkId("neg-1", "ticket-1"), TIMESTAMP, 1));
    repository.saveAndFlush(
        new ForwardedTimelineWatermark(
            new ForwardedTimelineWatermarkId("neg-1", "ticket-2"), TIMESTAMP, 3));

    assertThat(repository.findById(new ForwardedTimelineWatermarkId("neg-1", "ticket-1")))
        .get()
        .extracting(ForwardedTimelineWatermark::getLastTimestampCount)
        .isEqualTo(1);
    assertThat(repository.findById(new ForwardedTimelineWatermarkId("neg-1", "ticket-2")))
        .get()
        .extracting(ForwardedTimelineWatermark::getLastTimestampCount)
        .isEqualTo(3);
  }
}
