package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.HelpdeskCallbackHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class HelpdeskResourceTransitionHandler implements HelpdeskCallbackHandler {

  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedTransitionRepository originatedTransitionRepository;
  private final NegotiatorClient negotiatorClient;
  private final Map<HelpDeskType, HelpDeskAdapter> adapters;

  public HelpdeskResourceTransitionHandler(
      TicketMappingRepository ticketMappingRepository,
      OriginatedTransitionRepository originatedTransitionRepository,
      NegotiatorClient negotiatorClient,
      Map<HelpDeskType, HelpDeskAdapter> adapters) {
    this.ticketMappingRepository = ticketMappingRepository;
    this.originatedTransitionRepository = originatedTransitionRepository;
    this.negotiatorClient = negotiatorClient;
    this.adapters = adapters;
  }

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION;
  }

  @Override
  @Transactional
  public void handle(HelpdeskCallbackEvent event, OrganizationConfig config) {
    String ticketId = event.ticketId();
    String requestedEvent = event.resourceTransitionEvent();
    String helpdeskActor = event.helpdeskActor();

    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, config.organizationExternalId());

    if (mappings.isEmpty()) {
      log.warn(
          "No ticket mappings found for ticket '{}' org '{}' — skipping",
          ticketId,
          config.organizationExternalId());
      return;
    }

    HelpDeskAdapter adapter = adapters.get(config.type());

    if (mappings.size() == 1) {
      handlePerResource(
          mappings.getFirst(),
          requestedEvent,
          helpdeskActor,
          event.resourceState(),
          adapter,
          config);
    } else {
      handlePerNegotiation(
          mappings, requestedEvent, helpdeskActor, event.resourceState(), adapter, config);
    }
  }

  private void handlePerResource(
      TicketMapping mapping,
      String requestedEvent,
      String helpdeskActor,
      String resourceState,
      HelpDeskAdapter adapter,
      OrganizationConfig config) {

    String negotiationId = mapping.getId().getNegotiationId();
    String ticketId = mapping.getId().getHelpdeskTicketId();
    String resourceSourceId = mapping.getResourceSourceId();

    Set<String> allowedEvents =
        negotiatorClient.getPossibleResourceEvents(negotiationId, resourceSourceId, config);

    if (allowedEvents.contains(requestedEvent)) {
      log.info(
          "Applying transition '{}' to resource '{}' in negotiation '{}'",
          requestedEvent,
          resourceSourceId,
          negotiationId);
      negotiatorClient.sendResourceEvent(
          negotiationId, resourceSourceId, requestedEvent, helpdeskActor, config);
    } else {
      log.info(
          "Transition '{}' rejected for resource '{}' in negotiation '{}' — allowed: {}",
          requestedEvent,
          resourceSourceId,
          negotiationId,
          allowedEvents);
      adapter.updateResourceState(ticketId, resourceState, config);
      adapter.addSystemComment(
          ticketId, buildRejectionMessage(requestedEvent, allowedEvents), config);
    }
  }

  private void handlePerNegotiation(
      List<TicketMapping> mappings,
      String requestedEvent,
      String helpdeskActor,
      String resourceState,
      HelpDeskAdapter adapter,
      OrganizationConfig config) {

    String negotiationId = mappings.getFirst().getId().getNegotiationId();
    String ticketId = mappings.getFirst().getId().getHelpdeskTicketId();

    List<InvalidResource> invalidResources = new ArrayList<>();
    for (TicketMapping mapping : mappings) {
      Set<String> allowed =
          negotiatorClient.getPossibleResourceEvents(
              negotiationId, mapping.getResourceSourceId(), config);
      if (!allowed.contains(requestedEvent)) {
        invalidResources.add(new InvalidResource(mapping.getResourceSourceId(), allowed));
      }
    }

    if (!invalidResources.isEmpty()) {
      log.info(
          "Bulk transition '{}' rejected for negotiation '{}' — {} resource(s) cannot accept it",
          requestedEvent,
          negotiationId,
          invalidResources.size());
      adapter.updateResourceState(ticketId, resourceState, config);
      adapter.addSystemComment(
          ticketId, buildBulkRejectionMessage(requestedEvent, invalidResources), config);
      return;
    }

    List<Long> resourceIds =
        mappings.stream()
            .map(m -> Long.parseLong(m.getId().getResourceId()))
            .collect(Collectors.toList());

    originatedTransitionRepository.save(
        new OriginatedTransition(negotiationId, ticketId, mappings.size(), requestedEvent));

    String targetState =
        ResourceTransitionEvent.fromEvent(requestedEvent)
            .map(ResourceTransitionEvent::targetState)
            .orElse(requestedEvent);

    log.info(
        "Applying bulk transition '{}' to {} resource(s) in negotiation '{}'",
        requestedEvent,
        resourceIds.size(),
        negotiationId);
    negotiatorClient.sendBulkResourceEvent(
        negotiationId, resourceIds, targetState, helpdeskActor, config);
  }

  private static String buildRejectionMessage(String requested, Set<String> allowed) {
    String allowedReadable =
        allowed.isEmpty()
            ? "none"
            : allowed.stream()
                .map(HelpdeskResourceTransitionHandler::toHumanReadable)
                .sorted()
                .collect(Collectors.joining(", "));
    return "Transition '"
        + toHumanReadable(requested)
        + "' is not allowed from the current state. "
        + "Allowed transitions: "
        + allowedReadable;
  }

  private static String buildBulkRejectionMessage(
      String requested, List<InvalidResource> invalidResources) {
    var sb = new StringBuilder();
    sb.append("Transition '")
        .append(toHumanReadable(requested))
        .append("' cannot be applied to all resources:\n");
    for (var invalid : invalidResources) {
      String allowedReadable =
          invalid.allowedEvents().isEmpty()
              ? "none"
              : invalid.allowedEvents().stream()
                  .map(HelpdeskResourceTransitionHandler::toHumanReadable)
                  .sorted()
                  .collect(Collectors.joining(", "));
      sb.append("  - ")
          .append(invalid.resourceSourceId())
          .append(": allowed transitions: ")
          .append(allowedReadable)
          .append("\n");
    }
    sb.append("Please resolve the state discrepancy in the Negotiator UI.");
    return sb.toString();
  }

  /**
   * Converts {@code UPPER_SNAKE_CASE} to a sentence-cased string, e.g. {@code "Mark as available"}.
   */
  private static String toHumanReadable(String upperSnakeCase) {
    if (upperSnakeCase == null || upperSnakeCase.isBlank()) {
      return upperSnakeCase;
    }
    String lower = upperSnakeCase.replace('_', ' ').toLowerCase();
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private record InvalidResource(String resourceSourceId, Set<String> allowedEvents) {}
}
