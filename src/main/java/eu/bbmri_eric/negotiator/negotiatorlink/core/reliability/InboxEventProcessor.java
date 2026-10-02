package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskCallbackAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.HelpdeskEventRouter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventRouter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.RouteOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The decide-and-record step: processes one claimed inbox row in a single transaction and marks it
 * terminal ({@code PROCESSED}/{@code SKIPPED}/{@code FAILED}). Dispatch is by source — Negotiator
 * events go through {@link NegotiatorEventRouter}; help desk callbacks are re-resolved to their
 * {@link OrganizationConfig} + adapter, parsed, and routed through {@link HelpdeskEventRouter}.
 *
 * <p>In this phase (P1) handlers still perform outbound I/O inside this transaction, but on the
 * poller thread rather than the request thread, so the 8 s B1 budget is already protected. P2 moves
 * that I/O out into the outbox so this transaction becomes I/O-free.
 */
@Slf4j
@Service
public class InboxEventProcessor {

  private final InboxEventRepository inboxEventRepository;
  private final NegotiatorEventRouter negotiatorEventRouter;
  private final HelpdeskEventRouter helpdeskEventRouter;
  private final OrganizationConfigRegistry registry;
  private final Map<HelpdeskType, HelpdeskCallbackAdapter> callbackAdapters;
  private final NegotiationIdResolver negotiationIdResolver;
  private final ObjectMapper objectMapper;

  public InboxEventProcessor(
      InboxEventRepository inboxEventRepository,
      NegotiatorEventRouter negotiatorEventRouter,
      HelpdeskEventRouter helpdeskEventRouter,
      OrganizationConfigRegistry registry,
      Map<HelpdeskType, HelpdeskCallbackAdapter> callbackAdapters,
      NegotiationIdResolver negotiationIdResolver,
      ObjectMapper objectMapper) {
    this.inboxEventRepository = inboxEventRepository;
    this.negotiatorEventRouter = negotiatorEventRouter;
    this.helpdeskEventRouter = helpdeskEventRouter;
    this.registry = registry;
    this.callbackAdapters = callbackAdapters;
    this.negotiationIdResolver = negotiationIdResolver;
    this.objectMapper = objectMapper;
  }

  /** Claims a batch of unprocessed inbox rows in its own transaction and returns their ids. */
  @Transactional
  public List<Long> claimBatch(int limit) {
    return inboxEventRepository.claimReceivedBatch(limit).stream()
        .map(InboxEvent::getRowId)
        .toList();
  }

  /**
   * Processes a single claimed inbox row and marks it terminal. On a processing exception the
   * transaction rolls back and the exception propagates to the caller.
   */
  @Transactional
  public void process(Long rowId) {
    InboxEvent event = inboxEventRepository.findById(rowId).orElse(null);
    if (event == null || event.getStatus() != InboxStatus.RECEIVED) {
      return;
    }
    RouteOutcome outcome =
        EventSource.NEGOTIATOR.name().equals(event.getSource())
            ? dispatchNegotiator(event)
            : dispatchHelpdesk(event);
    outcome.resolvedEventType().ifPresent(event::setEventType);
    if (outcome.processed()) {
      markTerminal(event, InboxStatus.PROCESSED, null);
    } else {
      markTerminal(event, InboxStatus.SKIPPED, outcome.skipReason());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(Long rowId, String error) {
    inboxEventRepository
        .findById(rowId)
        .ifPresent(event -> markTerminal(event, InboxStatus.FAILED, truncate(error)));
  }

  private RouteOutcome dispatchNegotiator(InboxEvent event) {
    byte[] body = event.getPayload().getBytes(StandardCharsets.UTF_8);
    return negotiatorEventRouter.route(body);
  }

  private RouteOutcome dispatchHelpdesk(InboxEvent event) {
    Optional<OrganizationConfig> configOpt = registry.findByExternalId(event.getSource());
    if (configOpt.isEmpty()) {
      return RouteOutcome.skipped("unknown-org");
    }
    OrganizationConfig config = configOpt.get();
    HelpdeskCallbackAdapter adapter = callbackAdapters.get(config.type());
    if (adapter == null) {
      return RouteOutcome.skipped("no-adapter");
    }
    Map<String, Object> payload = parsePayload(event);
    Optional<HelpdeskCallbackEvent> parsed = adapter.parseCallback(payload);
    if (parsed.isEmpty()) {
      return RouteOutcome.skipped("unparseable");
    }
    HelpdeskCallbackEvent callbackEvent = parsed.get();
    String eventType = callbackEvent.type().name();
    if (negotiationIdResolver
        .resolveForHelpdeskCallback(callbackEvent.ticketId(), event.getSource())
        .isEmpty()) {
      return RouteOutcome.skipped("no-mapping", eventType);
    }
    helpdeskEventRouter.route(callbackEvent, config);
    return RouteOutcome.asProcessed(eventType);
  }

  private Map<String, Object> parsePayload(InboxEvent event) {
    try {
      return objectMapper.readValue(
          event.getPayload().getBytes(StandardCharsets.UTF_8), new TypeReference<>() {});
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Captured payload is not valid JSON", e);
    }
  }

  private void markTerminal(InboxEvent event, InboxStatus status, String skipReason) {
    event.setStatus(status);
    event.setSkipReason(skipReason);
    event.setProcessedAt(Instant.now());
    inboxEventRepository.save(event);
  }

  private String truncate(String message) {
    if (message == null) {
      return null;
    }
    return message.length() > 255 ? message.substring(0, 255) : message;
  }
}
