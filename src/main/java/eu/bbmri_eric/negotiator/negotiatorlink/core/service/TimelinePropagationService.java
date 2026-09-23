package eu.bbmri_eric.negotiator.negotiatorlink.core.service;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermark;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermarkId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TimelineEntry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.ForwardedTimelineWatermarkRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationTimelineEventDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forwards Negotiator timeline log entries to a help desk ticket as system comments, tracking a
 * per-{@code (negotiation, ticket)} watermark so each entry is forwarded at most once.
 *
 * <p>The Negotiator timeline has no per-entry ID and each timeline entry produces its own webhook.
 * Therefore, the watermark records both the last forwarded timestamp and how many entries sharing
 * that exact timestamp were already forwarded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelinePropagationService {

  private final NegotiatorClient negotiatorClient;
  private final ForwardedTimelineWatermarkRepository watermarkRepository;
  private final Map<HelpdeskType, HelpdeskClient> clients;

  /**
   * Forwards every timeline entry not yet forwarded to this ticket and actualizes the ticket's
   * watermark.
   */
  @Transactional
  public void propagate(String negotiationId, String ticketId, OrganizationConfig config) {
    List<TimelineEntry> timeline = fetchTimeline(negotiationId, config);
    if (timeline.isEmpty()) {
      return;
    }

    ForwardedTimelineWatermark watermark = lockOrNull(negotiationId, ticketId);
    List<TimelineEntry> delta = newEntries(timeline, watermark);
    if (delta.isEmpty()) {
      return;
    }

    HelpdeskClient client = clients.get(config.type());
    int forwarded = forward(client, ticketId, delta, config);
    if (forwarded == 0) {
      return;
    }

    int alreadyForwardedCount = timeline.size() - (delta.size() - forwarded);
    LocalDateTime forwardedThrough = timeline.get(alreadyForwardedCount - 1).timestamp();
    int countAtTimestamp = countAt(timeline.subList(0, alreadyForwardedCount), forwardedThrough);
    saveWatermark(negotiationId, ticketId, watermark, forwardedThrough, countAtTimestamp);
    log.info("Forwarded {} timeline entry(ies) to ticket '{}'", forwarded, ticketId);
  }

  /**
   * Records the current timeline tail as this ticket's watermark without forwarding anything, so
   * history predating ticket creation is not replayed. No-op if a watermark already exists.
   */
  @Transactional
  public void seed(String negotiationId, String ticketId, OrganizationConfig config) {
    if (lockOrNull(negotiationId, ticketId) != null) {
      return;
    }

    List<TimelineEntry> timeline = fetchTimeline(negotiationId, config);
    if (timeline.isEmpty()) {
      return;
    }

    LocalDateTime tail = timeline.getLast().timestamp();
    saveWatermark(negotiationId, ticketId, null, tail, countAt(timeline, tail));
    log.debug(
        "Seeded timeline watermark for negotiation '{}' ticket '{}'", negotiationId, ticketId);
  }

  private List<TimelineEntry> fetchTimeline(String negotiationId, OrganizationConfig config) {
    return negotiatorClient.getTimeline(negotiationId, config).stream()
        .map(TimelinePropagationService::toEntry)
        .toList();
  }

  private ForwardedTimelineWatermark lockOrNull(String negotiationId, String ticketId) {
    return watermarkRepository
        .findByIdForUpdate(new ForwardedTimelineWatermarkId(negotiationId, ticketId))
        .orElse(null);
  }

  /**
   * Selects the entries after the watermark. Entries are oldest-to-newest, so this is an ordered
   * scan: everything strictly after {@code lastTimestamp}, plus entries equal to {@code
   * lastTimestamp} beyond the already-forwarded count. With no watermark, the whole timeline is
   * new.
   */
  private List<TimelineEntry> newEntries(
      List<TimelineEntry> timeline, ForwardedTimelineWatermark watermark) {
    if (watermark == null) {
      return timeline;
    }

    LocalDateTime lastTimestamp = watermark.getLastTimestamp();
    int seenAtLastTimestamp = 0;
    for (int i = 0; i < timeline.size(); i++) {
      LocalDateTime entryTimestamp = timeline.get(i).timestamp();
      if (entryTimestamp.isAfter(lastTimestamp)) {
        return List.copyOf(timeline.subList(i, timeline.size()));
      }
      if (entryTimestamp.isEqual(lastTimestamp)) {
        seenAtLastTimestamp++;
        if (seenAtLastTimestamp > watermark.getLastTimestampCount()) {
          return List.copyOf(timeline.subList(i, timeline.size()));
        }
      }
    }
    return List.of();
  }

  /**
   * Forwards entries in order, stopping at the first failure so it is retried on the next event.
   */
  private int forward(
      HelpdeskClient client,
      String ticketId,
      List<TimelineEntry> entries,
      OrganizationConfig config) {
    int forwarded = 0;
    for (TimelineEntry entry : entries) {
      try {
        client.addSystemComment(ticketId, entry.text(), config);
        forwarded++;
      } catch (Exception e) {
        log.error(
            "Failed to forward timeline entry to ticket '{}' — will retry next event", ticketId, e);
        break;
      }
    }
    return forwarded;
  }

  private void saveWatermark(
      String negotiationId,
      String ticketId,
      ForwardedTimelineWatermark existing,
      LocalDateTime timestamp,
      int count) {
    ForwardedTimelineWatermark watermark =
        existing != null
            ? existing
            : new ForwardedTimelineWatermark(
                new ForwardedTimelineWatermarkId(negotiationId, ticketId), timestamp, count);
    watermark.setLastTimestamp(timestamp);
    watermark.setLastTimestampCount(count);
    watermarkRepository.save(watermark);
  }

  private static int countAt(List<TimelineEntry> timeline, LocalDateTime timestamp) {
    return (int) timeline.stream().filter(e -> e.timestamp().isEqual(timestamp)).count();
  }

  private static TimelineEntry toEntry(NegotiationTimelineEventDTO dto) {
    return new TimelineEntry(dto.getTriggeredBy(), dto.getText(), dto.getTimestamp());
  }
}
