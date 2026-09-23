package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    ticketToOrgExtId.forEach(
        (ticketId, orgExtId) ->
            postUpdateComment(
                negotiationId, ticketId, orgExtId, negotiation, resourceById, allMappings));
  }

  private void postUpdateComment(
      String negotiationId,
      String ticketId,
      String orgExtId,
      EntityModelNegotiationDTO negotiation,
      Map<String, EntityModelResourceWithStatusDTO> resourceById,
      List<TicketMapping> allMappings) {

    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.warn("Org '{}' not in config for ticket '{}' — skipping", orgExtId, ticketId);
      return;
    }
    HelpdeskClient client = clients.get(config.type());
    String body =
        buildBody(ticketId, config.ticketStrategy(), negotiation, resourceById, allMappings);
    try {
      client.addComment(ticketId, body, config);
      log.info(
          "Posted negotiation update comment to ticket '{}' (negotiation '{}')",
          ticketId,
          negotiationId);
    } catch (Exception e) {
      log.error(
          "Failed to post update comment to ticket '{}' for negotiation '{}'",
          ticketId,
          negotiationId,
          e);
    }
  }

  private String buildBody(
      String ticketId,
      TicketStrategy strategy,
      EntityModelNegotiationDTO negotiation,
      Map<String, EntityModelResourceWithStatusDTO> resourceById,
      List<TicketMapping> allMappings) {

    if (strategy == TicketStrategy.PER_NEGOTIATION) {
      // Collect all resources that belong to this ticket.
      List<EntityModelResourceWithStatusDTO> ticketResources =
          allMappings.stream()
              .filter(mapping -> mapping.getId().getHelpdeskTicketId().equals(ticketId))
              .map(mapping -> resourceById.get(mapping.getId().getResourceId()))
              .filter(Objects::nonNull)
              .toList();
      return NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithMultipleResources(
          negotiation, ticketResources);
    }

    // PER_RESOURCE: find the single resource for this ticket.
    EntityModelResourceWithStatusDTO resource =
        allMappings.stream()
            .filter(mapping -> mapping.getId().getHelpdeskTicketId().equals(ticketId))
            .findFirst()
            .map(mapping -> resourceById.get(mapping.getId().getResourceId()))
            .orElseThrow();
    return NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithSingleResource(
        negotiation, resource);
  }

  private static Map<String, String> getDistinctTicketIdToOrgExtId(List<TicketMapping> mappings) {
    Map<String, String> result = new LinkedHashMap<>();
    for (TicketMapping m : mappings) {
      result.putIfAbsent(m.getId().getHelpdeskTicketId(), m.getOrganizationExtId());
    }
    return result;
  }
}
