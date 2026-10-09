package eu.bbmri_eric.negotiator.negotiatorlink.core.service;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendBulkResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Validates a requested Negotiator resource lifecycle event against the resources covered by a
 * ticket and, if permitted, decides the outbound effect.
 */
@Slf4j
@Service
public class ResourceTransitionService {

  private final NegotiatorClient negotiatorClient;
  private final Map<String, String> eventToTargetState;

  /** A resource that cannot accept the requested event, with the events it currently permits. */
  public record InvalidResource(String resourceSourceId, Set<String> allowedEvents) {}

  /**
   * Describes why an {@link #apply} call cannot transition any resource: the resources that could
   * not accept the requested event, and whether all resources covered by the ticket are invalid.
   */
  public record Rejection(List<InvalidResource> invalidResources, boolean allInvalid) {}

  public record TransitionResult(List<PendingIntent> intents, Optional<Rejection> rejection) {

    static TransitionResult applied(PendingIntent intent) {
      return new TransitionResult(List.of(intent), Optional.empty());
    }

    static TransitionResult rejected(Rejection rejection) {
      return new TransitionResult(List.of(), Optional.of(rejection));
    }
  }

  public ResourceTransitionService(
      NegotiatorClient negotiatorClient, NegotiatorLinkProperties negotiatorLinkProperties) {
    this.negotiatorClient = negotiatorClient;
    this.eventToTargetState = negotiatorLinkProperties.getResourceEvents();
  }

  /**
   * Decides the effect of applying {@code requestedEvent} to the resources covered by {@code
   * mappings}. For {@link TicketStrategy#PER_NEGOTIATION} the event must be permitted for every
   * resource.
   *
   * @return a {@link TransitionResult} carrying the intent to enqueue, or a {@link Rejection}
   *     listing the resources that could not accept the event
   */
  public TransitionResult apply(
      List<TicketMapping> mappings, String requestedEvent, OrganizationConfig config) {
    if (config.ticketStrategy() == TicketStrategy.PER_RESOURCE) {
      return applyPerResource(mappings.getFirst(), requestedEvent, config);
    }
    return applyPerNegotiation(mappings, requestedEvent, config);
  }

  private TransitionResult applyPerResource(
      TicketMapping mapping, String requestedEvent, OrganizationConfig config) {
    String negotiationId = mapping.getId().getNegotiationId();
    String resourceSourceId = mapping.getResourceSourceId();

    Set<String> allowedEvents =
        negotiatorClient.getPossibleResourceEvents(negotiationId, resourceSourceId, config);

    if (!allowedEvents.contains(requestedEvent)) {
      log.info(
          "Transition '{}' rejected for resource '{}' in negotiation '{}' — allowed: {}",
          requestedEvent,
          resourceSourceId,
          negotiationId,
          allowedEvents);
      return TransitionResult.rejected(
          new Rejection(List.of(new InvalidResource(resourceSourceId, allowedEvents)), true));
    }

    log.info(
        "Deciding transition '{}' for resource '{}' in negotiation '{}'",
        requestedEvent,
        resourceSourceId,
        negotiationId);
    PendingIntent intent =
        new PendingIntent(
            OutboxIntentType.SEND_RESOURCE_EVENT,
            TargetKind.NEGOTIATION,
            negotiationId,
            negotiationId,
            config.organizationExternalId(),
            serialize(
                new SendResourceEventPayload(negotiationId, resourceSourceId, requestedEvent)));
    return TransitionResult.applied(intent);
  }

  private TransitionResult applyPerNegotiation(
      List<TicketMapping> mappings, String requestedEvent, OrganizationConfig config) {
    String negotiationId = mappings.getFirst().getId().getNegotiationId();

    List<InvalidResource> invalidResources = getInvalidResources(mappings, requestedEvent, config);
    if (!invalidResources.isEmpty()) {
      log.info(
          "Bulk transition '{}' rejected for negotiation '{}' — {} resource(s) cannot accept it",
          requestedEvent,
          negotiationId,
          invalidResources.size());
      return TransitionResult.rejected(
          new Rejection(invalidResources, mappings.size() == invalidResources.size()));
    }

    List<Long> resourceIds =
        mappings.stream().map(m -> Long.parseLong(m.getId().getResourceId())).toList();
    String targetState = eventToTargetState.get(requestedEvent);
    if (targetState == null) {
      throw new IllegalStateException(
          "No target state configured for event '%s'".formatted(requestedEvent));
    }

    log.info(
        "Deciding bulk transition '{}' for {} resource(s) in negotiation '{}'",
        requestedEvent,
        resourceIds.size(),
        negotiationId);
    PendingIntent intent =
        new PendingIntent(
            OutboxIntentType.SEND_BULK_RESOURCE_EVENT,
            TargetKind.NEGOTIATION,
            negotiationId,
            negotiationId,
            config.organizationExternalId(),
            serialize(new SendBulkResourceEventPayload(negotiationId, resourceIds, targetState)));
    return TransitionResult.applied(intent);
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
}
