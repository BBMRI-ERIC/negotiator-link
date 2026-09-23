package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermark;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermarkId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ForwardedTimelineWatermarkRepository
    extends JpaRepository<ForwardedTimelineWatermark, ForwardedTimelineWatermarkId> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT w FROM ForwardedTimelineWatermark w WHERE w.id = :id")
  Optional<ForwardedTimelineWatermark> findByIdForUpdate(
      @Param("id") ForwardedTimelineWatermarkId id);
}
