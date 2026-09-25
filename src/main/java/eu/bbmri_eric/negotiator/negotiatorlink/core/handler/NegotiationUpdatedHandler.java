package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationUpdatedWebhookEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NegotiationUpdatedHandler implements NegotiationEventHandler {

  private static final String EVENT_TYPE = "negotiation.updated";

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry registry;
  private final Map<HelpdeskType, HelpdeskClient> clients;
  private final TicketMappingRepository ticketMappingRepository;
  private final ObjectMapper objectMapper;

  /** Negotiation-wide data shared by every ticket's update comment, resolved once per event. */
  private record NegotiationContext(
      String negotiationId,
      EntityModelNegotiationDTO negotiation,
      Map<String, EntityModelResourceWithStatusDTO> resourceById,
      List<EntityModelResourceWithStatusDTO> allResources,
      List<TicketMapping> allMappings) {}

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public void handle(JsonNode data) {
    NegotiationUpdatedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationUpdatedWebhookEvent.class);

    String negotiationId = event.getNegotiationId();
    if (negotiationId == null) {
      log.warn("negotiation.updated has no 'negotiationId' field — discarding");
      return;
    }

    List<TicketMapping> allMappings = ticketMappingRepository.findByIdNegotiationId(negotiationId);
    if (allMappings.isEmpty()) {
      log.debug("No mapped tickets for negotiation '{}' — skipping update comment", negotiationId);
      return;
    }

    Map<String, String> ticketToOrgExtId = getDistinctTicketIdToOrgExtId(allMappings);
    String anyOrgExtId = allMappings.getFirst().getOrganizationExtId();
    OrganizationConfig fetchConfig = registry.findByExternalId(anyOrgExtId).orElseThrow();

    EntityModelNegotiationDTO negotiation =
        negotiatorClient.getNegotiation(negotiationId, fetchConfig);
    List<EntityModelResourceWithStatusDTO> allResources =
        negotiatorClient.getResources(negotiationId, fetchConfig);

    // Build a lookup from resourceId (String) → resource for fast per-ticket filtering below.
    Map<String, EntityModelResourceWithStatusDTO> resourceById =
        allResources.stream()
            .collect(
                Collectors.toMap(
                    resource -> String.valueOf(resource.getId()),
                    Function.identity(),
                    (existing, duplicate) -> existing));

    NegotiationContext context =
        new NegotiationContext(negotiationId, negotiation, resourceById, allResources, allMappings);

    ticketToOrgExtId.forEach(
        (ticketId, orgExtId) -> postUpdateComment(ticketId, orgExtId, context));
  }

  private void postUpdateComment(String ticketId, String orgExtId, NegotiationContext context) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.warn("Org '{}' not in config for ticket '{}' — skipping", orgExtId, ticketId);
      return;
    }
    HelpdeskClient client = clients.get(config.type());
    String body = buildBody(ticketId, config.ticketStrategy(), context);
    try {
      client.addComment(ticketId, body, CommentType.HTML, config);
      log.info(
          "Posted negotiation update comment to ticket '{}' (negotiation '{}')",
          ticketId,
          context.negotiationId());
    } catch (Exception e) {
      log.error(
          "Failed to post update comment to ticket '{}' for negotiation '{}'",
          ticketId,
          context.negotiationId(),
          e);
    }
  }

  private String buildBody(String ticketId, TicketStrategy strategy, NegotiationContext context) {
    if (strategy == TicketStrategy.PER_NEGOTIATION) {
      // Collect all resources that belong to this ticket.
      List<EntityModelResourceWithStatusDTO> ticketResources =
          context.allMappings().stream()
              .filter(mapping -> mapping.getId().getHelpdeskTicketId().equals(ticketId))
              .map(mapping -> context.resourceById().get(mapping.getId().getResourceId()))
              .filter(Objects::nonNull)
              .toList();
      return NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithMultipleResources(
          context.negotiation(), ticketResources, context.allResources());
    }

    // PER_RESOURCE: find the single resource for this ticket.
    EntityModelResourceWithStatusDTO resource =
        context.allMappings().stream()
            .filter(mapping -> mapping.getId().getHelpdeskTicketId().equals(ticketId))
            .findFirst()
            .map(mapping -> context.resourceById().get(mapping.getId().getResourceId()))
            .orElseThrow();
    return NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithSingleResource(
        context.negotiation(), resource, context.allResources());
  }

  private static Map<String, String> getDistinctTicketIdToOrgExtId(List<TicketMapping> mappings) {
    Map<String, String> result = new LinkedHashMap<>();
    for (TicketMapping m : mappings) {
      result.putIfAbsent(m.getId().getHelpdeskTicketId(), m.getOrganizationExtId());
    }
    return result;
  }
}
