package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.InvalidResource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HelpdeskResourceTransitionHandler
    implements HelpdeskCallbackHandler<ResourceTransitionEvent> {

  private final TicketMappingRepository ticketMappingRepository;
  private final ResourceTransitionService resourceTransitionService;
  private final Map<HelpdeskType, HelpdeskClient> clients;

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION;
  }

  @Override
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

    resourceTransitionService
        .apply(mappings, requestedEvent, config)
        .ifPresent(
            rejection -> {
              HelpdeskClient client = clients.get(config.type());
              String systemComment =
                  buildRejectionMessage(
                      requestedEvent, rejection.invalidResources(), rejection.allInvalid());
              client.updateResourceState(ticketId, transitionEvent.resourceState(), config);
              client.addSystemComment(ticketId, systemComment, config);
            });
  }

  private static String buildRejectionMessage(
      String requested, List<InvalidResource> invalidResources, boolean allInvalid) {
    if (allInvalid && hasAllSameAllowedEvents(invalidResources)) {
      return buildSingleRejectionMessage(requested, invalidResources.getFirst().allowedEvents());
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

  private static String buildSingleRejectionMessage(String requested, Set<String> allowed) {
    String allowedEvents = renderAllowedEvents(allowed);
    return "Transition '"
        + toHumanReadable(requested)
        + "' is not allowed from the current state. "
        + "Allowed transitions: "
        + allowedEvents;
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
