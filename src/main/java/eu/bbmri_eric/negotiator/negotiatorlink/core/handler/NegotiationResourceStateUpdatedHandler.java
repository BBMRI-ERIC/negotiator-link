package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.enumToText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.IntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
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
  private final OriginatedTransitionRepository originatedTransitionRepository;
  private final OrganizationConfigRegistry registry;
  private final ObjectMapper objectMapper;

  private record DispatchContext(
      String negotiationId, String ticketId, String orgExtId, OrganizationConfig config) {}

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public List<PendingIntent> handle(JsonNode data) {
    NegotiationResourceStateUpdatedWebhookEvent event = parseEvent(data);
    if (event == null) {
      return List.of();
    }

    DispatchContext context = resolveDispatchContext(event);
    if (context == null) {
      return List.of();
    }

    return dispatchStateUpdate(event, context);
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

  /**
   * Decides the state-update effects, deduplicating if the transition originated from the help
   * desk.
   */
  private List<PendingIntent> dispatchStateUpdate(
      NegotiationResourceStateUpdatedWebhookEvent event, DispatchContext context) {
    String negotiationId = event.getNegotiationId();
    String resourceSourceId = event.getResourceId();
    String toState = Objects.requireNonNull(event.getToState()).getValue();
    OriginatedTransition originated =
        originatedTransitionRepository
            .findByNegotiationIdAndTicketIdAndNewState(negotiationId, context.ticketId(), toState)
            .orElse(null);

    if (context.config().ticketStrategy() == TicketStrategy.PER_NEGOTIATION && originated != null) {
      return handleOriginatedTransition(originated, resourceSourceId, toState, context);
    }
    log.info(
        "Updating resource state to '{}' on ticket '{}' (Negotiator-triggered or from helpdesk with PER_RESOURCE strategy)",
        toState,
        context.ticketId());
    return stateUpdateIntents(resourceSourceId, toState, context);
  }

  private List<PendingIntent> handleOriginatedTransition(
      OriginatedTransition originated,
      String resourceSourceId,
      String toState,
      DispatchContext context) {
    String ticketId = context.ticketId();

    // Lock the row for the duration of this transaction so concurrent webhooks for the same
    // originated transition serialise here; exactly one thread observes the 0 -> 1 transition.
    OriginatedTransition locked =
        originatedTransitionRepository.findByIdForUpdate(originated.getRowId()).orElse(null);
    if (locked == null) {
      return List.of();
    }

    locked.setReceivedCount(locked.getReceivedCount() + 1);
    originatedTransitionRepository.save(locked);

    int received = locked.getReceivedCount();
    int expected = locked.getExpectedCount();

    List<PendingIntent> intents = new ArrayList<>();
    if (received == 1) {
      log.info(
          "First webhook for originated transition on ticket '{}' — updating state to '{}'",
          ticketId,
          toState);
      intents.addAll(stateUpdateIntents(resourceSourceId, toState, context));
    } else {
      log.debug(
          "Discarding redundant resource state transition {}/{} for ticket '{}'",
          received,
          expected,
          ticketId);
      intents.add(resourceChangedCommentIntent(resourceSourceId, toState, context));
    }

    if (received >= expected) {
      originatedTransitionRepository.deleteById(locked.getRowId());
      log.debug(
          "Consumed all {} webhooks for ticket '{}' — removed originated_transition row",
          expected,
          ticketId);
    }
    return intents;
  }

  /** Builds the SET_RESOURCE_STATE (+ system comment, + optional SET_TICKET_STATE) intents. */
  private List<PendingIntent> stateUpdateIntents(
      String resourceSourceId, String toState, DispatchContext context) {
    List<PendingIntent> intents = new ArrayList<>();
    intents.add(
        intent(
            IntentType.SET_RESOURCE_STATE,
            context,
            serialize(new SetStatePayload(SetStatePayload.FIELD_RESOURCE_STATE, toState))));

    intents.add(resourceChangedCommentIntent(resourceSourceId, toState, context));

    String ticketState = context.config().resourceStateToTicketState().get(toState);
    if (ticketState != null) {
      intents.add(
          intent(
              IntentType.SET_TICKET_STATE,
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
        IntentType.ADD_SYSTEM_COMMENT, context, serialize(new AddSystemCommentPayload(message)));
  }

  private PendingIntent intent(IntentType type, DispatchContext context, String payload) {
    return new PendingIntent(
        type,
        TargetKind.TICKET,
        context.ticketId(),
        context.negotiationId(),
        context.orgExtId(),
        payload);
  }
}
