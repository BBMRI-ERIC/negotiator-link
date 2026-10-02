package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durably captures an authenticated inbound delivery into the inbox before any processing. */
@Slf4j
@Service
public class InboxService {

  private final InboxEventRepository inboxEventRepository;
  private final InboxService self;

  public InboxService(InboxEventRepository inboxEventRepository, @Lazy InboxService self) {
    this.inboxEventRepository = inboxEventRepository;
    this.self = self;
  }

  /**
   * Captures an inbound delivery.
   *
   * @param source {@code NEGOTIATOR} or the organization external id
   * @param deliveryId the delivery id ({@code Webhook-Id} / adapter callback id); blank falls back
   *     to a content hash so dedup still functions on platforms without a delivery id
   * @param rawBody the raw verified request body
   * @return the persisted event, or empty when the delivery was already captured (duplicate)
   */
  public Optional<InboxEvent> capture(String source, String deliveryId, byte[] rawBody) {
    String effectiveDeliveryId = resolveDeliveryId(deliveryId, rawBody);
    String payload = new String(rawBody, StandardCharsets.UTF_8);
    try {
      InboxEvent event = self.insert(source, effectiveDeliveryId, payload);
      log.info(
          "Captured inbound event {} from '{}' (delivery {})",
          event.getRowId(),
          source,
          effectiveDeliveryId);
      return Optional.of(event);
    } catch (DataIntegrityViolationException e) {
      log.debug(
          "Duplicate inbound delivery '{}' from '{}' — acknowledged, not reprocessed",
          effectiveDeliveryId,
          source);
      return Optional.empty();
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public InboxEvent insert(String source, String deliveryId, String payload) {
    return inboxEventRepository.saveAndFlush(new InboxEvent(source, deliveryId, null, payload));
  }

  private String resolveDeliveryId(String deliveryId, byte[] rawBody) {
    if (deliveryId == null || deliveryId.isBlank()) {
      return "sha256:" + HashUtil.sha256Hex(new String(rawBody, StandardCharsets.UTF_8));
    }
    return deliveryId;
  }
}
