package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.IntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.InvalidResource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.TransitionResult;
import java.util.ArrayList;
import java.util.List;
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

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION;
  }

  @Override
  public List<PendingIntent> handle(
      ResourceTransitionEvent transitionEvent, OrganizationConfig config) {
    String ticketId = transitionEvent.ticketId();
    String requestedEvent = transitionEvent.resourceTransitionEvent();

    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, config.organizationExternalId());

    TransitionResult result = resourceTransitionService.apply(mappings, requestedEvent, config);
    if (result.rejection().isEmpty()) {
      return result.intents();
    }

    String negotiationId = mappings.getFirst().getId().getNegotiationId();
    var rejection = result.rejection().get();
    String systemComment =
        buildRejectionMessage(requestedEvent, rejection.invalidResources(), rejection.allInvalid());
    List<PendingIntent> intents = new ArrayList<>();
    intents.add(
        ticketIntent(
            IntentType.SET_RESOURCE_STATE,
            ticketId,
            negotiationId,
            config,
            serialize(
                new SetStatePayload(
                    SetStatePayload.FIELD_RESOURCE_STATE, transitionEvent.resourceState()))));
    intents.add(
        ticketIntent(
            IntentType.ADD_SYSTEM_COMMENT,
            ticketId,
            negotiationId,
            config,
            serialize(new AddSystemCommentPayload(systemComment))));
    return intents;
  }

  private PendingIntent ticketIntent(
      IntentType type,
      String ticketId,
      String negotiationId,
      OrganizationConfig config,
      String payload) {
    return new PendingIntent(
        type, TargetKind.TICKET, ticketId, negotiationId, config.organizationExternalId(), payload);
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
