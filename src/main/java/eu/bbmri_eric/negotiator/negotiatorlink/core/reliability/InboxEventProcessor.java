package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskCallbackAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HandlerResult;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.HelpdeskEventRouter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventRouter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.RouteOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@AllArgsConstructor
public class InboxEventProcessor {

  private final TicketMappingRepository ticketMappingRepository;
  private final InboxEventRepository inboxEventRepository;
  private final NegotiatorEventRouter negotiatorEventRouter;
  private final HelpdeskEventRouter helpdeskEventRouter;
  private final OrganizationConfigRegistry registry;
  private final Map<HelpdeskType, HelpdeskCallbackAdapter> callbackAdapters;
  private final OutboxService outboxService;
  private final FailureClassifier failureClassifier;
  private final OriginatedPostRepository originatedPostRepository;
  private final ObjectMapper objectMapper;
  private final NegotiatorLinkProperties properties;

  /** Routes a claimed inbox event. */
  public RouteOutcome buildOutcome(InboxEvent event) {
    return EventSource.NEGOTIATOR.name().equals(event.getSource())
        ? dispatchNegotiator(event)
        : dispatchHelpdesk(event);
  }

  /** Persists the resulted RouteOutcome from handler. */
  @Transactional
  public void commit(Long rowId, RouteOutcome outcome) {
    InboxEvent event = inboxEventRepository.findById(rowId).orElse(null);
    if (event == null || event.getStatus() != InboxStatus.RECEIVED) {
      return;
    }
    outcome.resolvedEventType().ifPresent(event::setEventType);
    if (outcome.processed()) {
      outcome
          .originatedPost()
          .ifPresent(
              post -> {
                boolean isCreate =
                    outcome.intents().stream()
                        .anyMatch(
                            intent -> intent.outboxIntentType() == OutboxIntentType.CREATE_POST);
                if (isCreate) {
                  originatedPostRepository.save(post);
                } else {
                  originatedPostRepository.deleteById(post.getRowId());
                }
              });
      outboxService.enqueue(outcome.intents(), event);
      markTerminal(event, InboxStatus.PROCESSED, null, null);
    } else {
      markTerminal(event, InboxStatus.SKIPPED, outcome.skipReason(), null);
    }
  }

  /**
   * Records a processing failure in its own transaction. A transient failure (e.g. Negotiator
   * unreachable during a read) leaves the row {@code RECEIVED} with a backoff so the poller
   * re-picks it, until the attempt cap is reached.
   */
  @Transactional
  public void recordFailure(Long rowId, Throwable error) {
    InboxEvent event = inboxEventRepository.findById(rowId).orElse(null);
    if (event == null) {
      return;
    }
    FailureClass failureClass = failureClassifier.classify(error);
    event.setAttemptCount(event.getAttemptCount() + 1);
    NegotiatorLinkProperties.InboxProperties inbox = properties.getInbox();
    if (failureClass == FailureClass.TRANSIENT
        && event.getAttemptCount() < inbox.getMaxAttempts()) {
      event.setNextAttemptAt(
          Instant.now()
              .plus(
                  BackoffPolicy.nextDelay(
                      event.getAttemptCount(), inbox.getBaseBackoff(), inbox.getMaxBackoff())));
      inboxEventRepository.save(event);
      log.warn(
          "Inbox row {} transient failure (attempt {}) — will retry",
          rowId,
          event.getAttemptCount(),
          error);
    } else {
      markTerminal(event, InboxStatus.FAILED, null, truncate(error.getMessage()));
      log.error(
          "Inbox row {} failed ({}, attempt {}) — marked FAILED",
          rowId,
          failureClass,
          event.getAttemptCount(),
          error);
    }
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
    if (resolveNegotiationIdForHelpdeskCallback(callbackEvent.ticketId(), event.getSource())
        .isEmpty()) {
      return RouteOutcome.skipped("no-mapping", eventType);
    }
    HandlerResult handlerResult = helpdeskEventRouter.route(callbackEvent, config);
    return RouteOutcome.asProcessed(
        eventType, handlerResult.intents(), handlerResult.originatedPost());
  }

  private Map<String, Object> parsePayload(InboxEvent event) {
    try {
      return objectMapper.readValue(
          event.getPayload().getBytes(StandardCharsets.UTF_8), new TypeReference<>() {});
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Captured payload is not valid JSON", e);
    }
  }

  private void markTerminal(
      InboxEvent event, InboxStatus status, String skipReason, String lastError) {
    event.setStatus(status);
    event.setSkipReason(skipReason);
    event.setLastError(lastError);
    event.setProcessedAt(Instant.now());
    inboxEventRepository.save(event);
  }

  private Optional<String> resolveNegotiationIdForHelpdeskCallback(
      String ticketId, String organizationExtId) {
    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, organizationExtId);
    return mappings.stream().findFirst().map(m -> m.getId().getNegotiationId());
  }

  private String truncate(String message) {
    if (message == null) {
      return null;
    }
    return message.length() > 255 ? message.substring(0, 255) : message;
  }
}
