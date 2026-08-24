package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationResourceStateUpdatedWebhookEvent;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class NegotiationResourceStateUpdatedHandler implements NegotiatorEventHandler {

  private static final String EVENT_TYPE = "negotiation.resource.state.updated";

  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedTransitionRepository originatedTransitionRepository;
  private final OrganizationConfigRegistry registry;
  private final Map<HelpDeskType, HelpDeskAdapter> adapters;
  private final ObjectMapper objectMapper;

  public NegotiationResourceStateUpdatedHandler(
      TicketMappingRepository ticketMappingRepository,
      OriginatedTransitionRepository originatedTransitionRepository,
      OrganizationConfigRegistry registry,
      Map<HelpDeskType, HelpDeskAdapter> adapters,
      ObjectMapper objectMapper) {
    this.ticketMappingRepository = ticketMappingRepository;
    this.originatedTransitionRepository = originatedTransitionRepository;
    this.registry = registry;
    this.adapters = adapters;
    this.objectMapper = objectMapper;
  }

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  @Transactional
  public void handle(JsonNode data) {
    ResourceStateUpdate update = parseEvent(data);
    if (update == null) {
      return;
    }

    DispatchContext context = resolveDispatchContext(update);
    if (context == null) {
      return;
    }

    dispatchStateUpdate(update, context);
  }

  /** Parses and validates the webhook event, or returns {@code null} if it is incomplete. */
  private ResourceStateUpdate parseEvent(JsonNode data) {
    NegotiationResourceStateUpdatedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationResourceStateUpdatedWebhookEvent.class);

    String negotiationId = event.getNegotiationId();
    String resourceId = event.getResourceId();
    String toState = event.getToState() != null ? event.getToState().getValue() : null;

    if (negotiationId == null || resourceId == null || toState == null) {
      log.warn("Incomplete resource state update event — skipping");
      return null;
    }
    return new ResourceStateUpdate(negotiationId, resourceId, toState);
  }

  /**
   * Resolves the ticket mapping, org config, and adapter for the update, or returns {@code null} if
   * the resource is not managed here or is misconfigured.
   */
  private DispatchContext resolveDispatchContext(ResourceStateUpdate update) {
    Optional<TicketMapping> mappingOpt =
        ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            update.negotiationId(), update.resourceId());
    if (mappingOpt.isEmpty()) {
      log.debug(
          "No ticket mapping for negotiation '{}' resource '{}' — not managed here",
          update.negotiationId(),
          update.resourceId());
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

    HelpDeskAdapter adapter = adapters.get(config.type());
    if (adapter == null) {
      log.error("No adapter for type '{}' (org '{}')", config.type(), orgExtId);
      return null;
    }

    return new DispatchContext(ticketId, config, adapter);
  }

  /** Applies the state update, deduplicating it if it originated from the help desk. */
  private void dispatchStateUpdate(ResourceStateUpdate update, DispatchContext context) {
    OriginatedTransition originated =
        originatedTransitionRepository
            .findByNegotiationIdAndTicketIdAndNewState(
                update.negotiationId(), context.ticketId(), update.toState())
            .orElse(null);

    try {
      if (context.config.ticketStrategy() == TicketStrategy.PER_NEGOTIATION && originated != null) {
        handleOriginatedTransition(originated, update.toState(), context);
      } else {
        log.info(
            "Updating resource state to '{}' on ticket '{}' (Negotiator-triggered or from helpdesk with PER_RESOURCE strategy)",
            update.toState(),
            context.ticketId());
        context
            .adapter()
            .updateResourceState(context.ticketId(), update.toState(), context.config());
      }
    } catch (Exception e) {
      log.error(
          "Failed to update resource state to '{}' on ticket '{}' for negotiation '{}' resource '{}'",
          update.toState(),
          context.ticketId(),
          update.negotiationId(),
          update.resourceId(),
          e);
    }
  }

  private void handleOriginatedTransition(
      OriginatedTransition originated, String toState, DispatchContext context) {
    String ticketId = context.ticketId();

    originatedTransitionRepository.incrementReceivedCount(originated.getRowId());
    // Re-fetch to get the updated count after the atomic increment
    OriginatedTransition updated =
        originatedTransitionRepository.findById(originated.getRowId()).orElse(null);
    if (updated == null) {
      return;
    }

    int received = updated.getReceivedCount();
    int expected = updated.getExpectedCount();

    if (received == 1) {
      log.info(
          "First webhook for originated transition on ticket '{}' — updating state to '{}'",
          ticketId,
          toState);
      try {
        context.adapter().updateResourceState(ticketId, toState, context.config());
      } catch (Exception e) {
        log.error(
            "Failed to update resource state to '{}' on ticket '{}' for originated transition",
            toState,
            ticketId,
            e);
      }
    } else {
      log.debug(
          "Discarding redundant resource state webhook {}/{} for ticket '{}'",
          received,
          expected,
          ticketId);
    }

    if (received >= expected) {
      originatedTransitionRepository.deleteByNegotiationIdAndTicketIdAndNewState(
          updated.getNegotiationId(), updated.getTicketId(), updated.getNewState());
      log.debug(
          "Consumed all {} webhooks for ticket '{}' — removed originated_transitions row",
          expected,
          ticketId);
    }
  }

  /** The validated fields extracted from the webhook event. */
  private record ResourceStateUpdate(String negotiationId, String resourceId, String toState) {}

  /** The resolved target for a state update: which ticket, and how to reach it. */
  private record DispatchContext(
      String ticketId, OrganizationConfig config, HelpDeskAdapter adapter) {}
}
