package eu.bbmri_eric.negotiator.negotiatorlink.core.service;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates a requested Negotiator resource lifecycle event against the resources covered by a
 * ticket and, if permitted, applies it. The service performs no help-desk-side reactions: it
 * returns an {@link Optional} {@link Rejection} describing why the transition was refused (empty
 * when it was applied) so each caller can decide how to react (e.g. post a rejection comment, or
 * silently drop).
 */
@Slf4j
@Service
public class ResourceTransitionService {

  private final NegotiatorClient negotiatorClient;
  private final OriginatedTransitionRepository originatedTransitionRepository;
  private final Map<String, String> eventToTargetState;

  /** A resource that cannot accept the requested event, with the events it currently permits. */
  public record InvalidResource(String resourceSourceId, Set<String> allowedEvents) {}

  /**
   * Describes why an {@link #apply} call did not transition any resource: the resources that could
   * not accept the requested event, and whether that was <em>all</em> resources covered by the
   * ticket.
   */
  public record Rejection(List<InvalidResource> invalidResources, boolean allInvalid) {}

  public ResourceTransitionService(
      NegotiatorClient negotiatorClient,
      OriginatedTransitionRepository originatedTransitionRepository,
      NegotiatorLinkProperties negotiatorLinkProperties) {
    this.negotiatorClient = negotiatorClient;
    this.originatedTransitionRepository = originatedTransitionRepository;
    this.eventToTargetState = negotiatorLinkProperties.getResourceEvents();
  }

  /**
   * Applies {@code requestedEvent} to the resources covered by {@code mappings}. For {@link
   * TicketStrategy#PER_RESOURCE} the single mapped resource is transitioned via a lifecycle event;
   * for {@link TicketStrategy#PER_NEGOTIATION} the event must be permitted for every resource and
   * is applied via the bulk endpoint, recording an {@link OriginatedTransition} for echo
   * deduplication.
   *
   * @return an empty {@link Optional} if the transition was applied, or a {@link Rejection} listing
   *     the resources that could not accept it
   */
  @Transactional
  public Optional<Rejection> apply(
      List<TicketMapping> mappings, String requestedEvent, OrganizationConfig config) {
    if (config.ticketStrategy() == TicketStrategy.PER_RESOURCE) {
      return applyPerResource(mappings.getFirst(), requestedEvent, config);
    }
    return applyPerNegotiation(mappings, requestedEvent, config);
  }

  private Optional<Rejection> applyPerResource(
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
      return Optional.of(
          new Rejection(List.of(new InvalidResource(resourceSourceId, allowedEvents)), true));
    }

    log.info(
        "Applying transition '{}' to resource '{}' in negotiation '{}'",
        requestedEvent,
        resourceSourceId,
        negotiationId);
    negotiatorClient.sendResourceEvent(negotiationId, resourceSourceId, requestedEvent, config);
    return Optional.empty();
  }

  private Optional<Rejection> applyPerNegotiation(
      List<TicketMapping> mappings, String requestedEvent, OrganizationConfig config) {
    String negotiationId = mappings.getFirst().getId().getNegotiationId();
    String ticketId = mappings.getFirst().getId().getHelpdeskTicketId();

    List<InvalidResource> invalidResources = getInvalidResources(mappings, requestedEvent, config);
    if (!invalidResources.isEmpty()) {
      log.info(
          "Bulk transition '{}' rejected for negotiation '{}' — {} resource(s) cannot accept it",
          requestedEvent,
          negotiationId,
          invalidResources.size());
      return Optional.of(
          new Rejection(invalidResources, mappings.size() == invalidResources.size()));
    }

    List<Long> resourceIds =
        mappings.stream().map(m -> Long.parseLong(m.getId().getResourceId())).toList();
    String targetState = eventToTargetState.get(requestedEvent);
    if (targetState == null) {
      throw new IllegalStateException(
          "No target state configured for event '%s'".formatted(requestedEvent));
    }

    originatedTransitionRepository.save(
        new OriginatedTransition(negotiationId, ticketId, mappings.size(), targetState));

    log.info(
        "Applying bulk transition '{}' to {} resource(s) in negotiation '{}'",
        requestedEvent,
        resourceIds.size(),
        negotiationId);
    negotiatorClient.sendBulkResourceEvent(negotiationId, resourceIds, targetState, config);
    return Optional.empty();
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
