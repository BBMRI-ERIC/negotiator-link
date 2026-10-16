package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.enumToText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationCache;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationResourceStateUpdatedWebhookEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NegotiationResourceStateUpdatedHandler implements NegotiationEventHandler {

  private static final String EVENT_TYPE = "negotiation.resource.state.updated";

  private final TicketMappingRepository ticketMappingRepository;
  private final OrganizationConfigRegistry registry;
  private final PendingTicketCreationCache pendingTicketCreationCache;
  private final ObjectMapper objectMapper;

  private record DispatchContext(
      String negotiationId, String ticketId, String orgExtId, OrganizationConfig config) {}

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public HandlerResult handle(JsonNode data) {
    NegotiationResourceStateUpdatedWebhookEvent event = parseEvent(data);
    if (event == null) {
      return HandlerResult.empty();
    }

    DispatchContext context = resolveDispatchContext(event);
    if (context == null) {
      return HandlerResult.empty();
    }

    return HandlerResult.of(dispatchStateUpdate(event, context));
  }

  /** Parses and validates the webhook event, or returns {@code null} if it is incomplete. */
  private NegotiationResourceStateUpdatedWebhookEvent parseEvent(JsonNode data) {
    NegotiationResourceStateUpdatedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationResourceStateUpdatedWebhookEvent.class);

    String negotiationId = event.getNegotiationId();
    String resourceId = event.getResourceId();
    String toState = event.getToState() != null ? event.getToState().getValue() : null;

    if (negotiationId == null || resourceId == null || toState == null) {
      log.warn("Incomplete resource state update event — skipping");
      return null;
    }
    return event;
  }

  private DispatchContext resolveDispatchContext(
      NegotiationResourceStateUpdatedWebhookEvent event) {
    Optional<TicketMapping> mappingOpt =
        ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            event.getNegotiationId(), event.getResourceId());
    if (mappingOpt.isEmpty()) {
      boolean anyMappingExists =
          !ticketMappingRepository.findByIdNegotiationId(event.getNegotiationId()).isEmpty();
      if (!anyMappingExists && pendingTicketCreationCache.isTracked(event.getNegotiationId())) {
        throw new PendingTicketCreationException(event.getNegotiationId());
      }
      log.debug(
          "No ticket mapping for negotiation '{}' resource '{}' — not managed here",
          event.getNegotiationId(),
          event.getResourceId());
      return null;
    }

    TicketMapping mapping = mappingOpt.get();
    String ticketId = mapping.getId().getHelpdeskTicketId();
    String orgExtId = mapping.getOrganizationExtId();

    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.warn("Org '{}' not in config for ticket '{}' — skipping", orgExtId, ticketId);
      return null;
    }

    return new DispatchContext(event.getNegotiationId(), ticketId, orgExtId, config);
  }

  private List<PendingIntent> dispatchStateUpdate(
      NegotiationResourceStateUpdatedWebhookEvent event, DispatchContext context) {
    String resourceSourceId = event.getResourceId();
    String toState = Objects.requireNonNull(event.getToState()).getValue();
    log.info("Updating resource state to '{}' on ticket '{}'", toState, context.ticketId());
    return stateUpdateIntents(resourceSourceId, toState, context);
  }

  /** Builds the SET_RESOURCE_STATE (+ system comment, + optional SET_TICKET_STATE) intents. */
  private List<PendingIntent> stateUpdateIntents(
      String resourceSourceId, String toState, DispatchContext context) {
    List<PendingIntent> intents = new ArrayList<>();
    intents.add(
        intent(
            OutboxIntentType.SET_RESOURCE_STATE,
            context,
            serialize(new SetStatePayload(SetStatePayload.FIELD_RESOURCE_STATE, toState))));

    intents.add(resourceChangedCommentIntent(resourceSourceId, toState, context));

    String ticketState = context.config().resourceStateToTicketState().get(toState);
    if (ticketState != null) {
      intents.add(
          intent(
              OutboxIntentType.SET_TICKET_STATE,
              context,
              serialize(new SetStatePayload(SetStatePayload.FIELD_TICKET_STATE, ticketState))));
    }
    return intents;
  }

  private PendingIntent resourceChangedCommentIntent(
      String resourceSourceId, String toState, DispatchContext context) {
    String message =
        String.format(
            "Resource '%s' changed the status to '%s'", resourceSourceId, enumToText(toState));
    return intent(
        OutboxIntentType.ADD_SYSTEM_COMMENT,
        context,
        serialize(new AddSystemCommentPayload(message)));
  }

  private PendingIntent intent(OutboxIntentType type, DispatchContext context, String payload) {
    return new PendingIntent(
        type,
        TargetKind.TICKET,
        context.ticketId(),
        context.negotiationId(),
        context.orgExtId(),
        payload);
  }
}
