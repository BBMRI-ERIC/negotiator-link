package eu.bbmri_eric.negotiator.negotiatorlink.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "forwarded_timeline_watermark")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class ForwardedTimelineWatermark {

  @EmbeddedId private ForwardedTimelineWatermarkId id;

  @Column(name = "last_timestamp", nullable = false)
  private LocalDateTime lastTimestamp;

  @Column(name = "last_timestamp_count", nullable = false)
  private int lastTimestampCount;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public ForwardedTimelineWatermark(
      ForwardedTimelineWatermarkId id, LocalDateTime lastTimestamp, int lastTimestampCount) {
    this.id = id;
    this.lastTimestamp = lastTimestamp;
    this.lastTimestampCount = lastTimestampCount;
  }
}
