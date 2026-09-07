package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.NegotiatorResourceEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class HelpdeskResourceTransitionHandler
    implements HelpdeskCallbackHandler<ResourceTransitionEvent> {

  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedTransitionRepository originatedTransitionRepository;
  private final NegotiatorClient negotiatorClient;
  private final Map<HelpdeskType, HelpdeskClient> clients;

  private record InvalidResource(String resourceSourceId, Set<String> allowedEvents) {}

  public HelpdeskResourceTransitionHandler(
      TicketMappingRepository ticketMappingRepository,
      OriginatedTransitionRepository originatedTransitionRepository,
      NegotiatorClient negotiatorClient,
      Map<HelpdeskType, HelpdeskClient> clients) {
    this.ticketMappingRepository = ticketMappingRepository;
    this.originatedTransitionRepository = originatedTransitionRepository;
    this.negotiatorClient = negotiatorClient;
    this.clients = clients;
  }

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION;
  }

  @Override
  @Transactional
  public void handle(ResourceTransitionEvent transitionEvent, OrganizationConfig config) {
    String ticketId = transitionEvent.ticketId();
    String requestedEvent = transitionEvent.resourceTransitionEvent();

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

    HelpdeskClient client = clients.get(config.type());

    if (config.ticketStrategy() == TicketStrategy.PER_RESOURCE) {
      handlePerResource(
          mappings.getFirst(), requestedEvent, transitionEvent.resourceState(), client, config);
    } else {
      handlePerNegotiation(
          mappings, requestedEvent, transitionEvent.resourceState(), client, config);
    }
  }

  private void handlePerResource(
      TicketMapping mapping,
      String requestedEvent,
      String resourceState,
      HelpdeskClient client,
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
      negotiatorClient.sendResourceEvent(negotiationId, resourceSourceId, requestedEvent, config);
    } else {
      log.info(
          "Transition '{}' rejected for resource '{}' in negotiation '{}' — allowed: {}",
          requestedEvent,
          resourceSourceId,
          negotiationId,
          allowedEvents);
      client.updateResourceState(ticketId, resourceState, config);
      client.addSystemComment(
          ticketId, buildRejectionMessage(requestedEvent, allowedEvents), config);
    }
  }

  private void handlePerNegotiation(
      List<TicketMapping> mappings,
      String requestedEvent,
      String resourceState,
      HelpdeskClient client,
      OrganizationConfig config) {

    String negotiationId = mappings.getFirst().getId().getNegotiationId();
    String ticketId = mappings.getFirst().getId().getHelpdeskTicketId();

    List<InvalidResource> invalidResources = getInvalidResources(mappings, requestedEvent, config);
    if (!invalidResources.isEmpty()) {
      log.info(
          "Bulk transition '{}' rejected for negotiation '{}' — {} resource(s) cannot accept it",
          requestedEvent,
          negotiationId,
          invalidResources.size());
      String systemComment =
          buildBulkRejectionMessage(
              requestedEvent, invalidResources, mappings.size() == invalidResources.size());
      client.updateResourceState(ticketId, resourceState, config);
      client.addSystemComment(ticketId, systemComment, config);
      return;
    }

    List<Long> resourceIds =
        mappings.stream().map(m -> Long.parseLong(m.getId().getResourceId())).toList();
    String targetState =
        NegotiatorResourceEvent.fromEvent(requestedEvent)
            .map(NegotiatorResourceEvent::targetState)
            .orElse(requestedEvent);

    originatedTransitionRepository.save(
        new OriginatedTransition(negotiationId, ticketId, mappings.size(), targetState));

    log.info(
        "Applying bulk transition '{}' to {} resource(s) in negotiation '{}'",
        requestedEvent,
        resourceIds.size(),
        negotiationId);
    negotiatorClient.sendBulkResourceEvent(negotiationId, resourceIds, targetState, config);
  }

  private List<InvalidResource> getInvalidResources(
      List<TicketMapping> mappings, String requestedEvent, OrganizationConfig config) {
    String negotiationId = mappings.getFirst().getId().getNegotiationId();
    return mappings.stream()
        .map(
            m ->
                new InvalidResource(
                    m.getResourceSourceId(),
                    negotiatorClient.getPossibleResourceEvents(
                        negotiationId, m.getResourceSourceId(), config)))
        .filter(r -> !r.allowedEvents().contains(requestedEvent))
        .toList();
  }

  private static String buildRejectionMessage(String requested, Set<String> allowed) {
    String allowedEvents = renderAllowedEvents(allowed);
    return "Transition '"
        + toHumanReadable(requested)
        + "' is not allowed from the current state. "
        + "Allowed transitions: "
        + allowedEvents;
  }

  private static String buildBulkRejectionMessage(
      String requested, List<InvalidResource> invalidResources, boolean allInvalid) {
    if (allInvalid && hasAllSameAllowedEvents(invalidResources)) {
      return buildRejectionMessage(requested, invalidResources.getFirst().allowedEvents());
    }
    var sb = new StringBuilder();
    sb.append("Transition '")
        .append(toHumanReadable(requested))
        .append("' cannot be applied to all resources:\n");
    for (var invalid : invalidResources) {
      String allowedEvents = renderAllowedEvents(invalid.allowedEvents());
      sb.append("  - ")
          .append(invalid.resourceSourceId())
          .append(": allowed transitions: ")
          .append(allowedEvents)
          .append("\n");
    }
    sb.append("Please resolve the state discrepancy in the Negotiator UI.");
    return sb.toString();
  }

  private static boolean hasAllSameAllowedEvents(List<InvalidResource> invalidResources) {
    Set<String> firstAllowedEvents = invalidResources.getFirst().allowedEvents();
    for (InvalidResource invalid : invalidResources) {
      if (!invalid.allowedEvents().equals(firstAllowedEvents)) {
        return false;
      }
    }
    return true;
  }

  private static String renderAllowedEvents(Set<String> allowed) {
    return allowed.isEmpty()
        ? "none"
        : allowed.stream()
            .map(HelpdeskResourceTransitionHandler::toHumanReadable)
            .sorted()
            .collect(Collectors.joining(", "));
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
}
