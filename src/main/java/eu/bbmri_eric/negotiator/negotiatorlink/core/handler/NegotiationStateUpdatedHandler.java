package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationStateUpdatedWebhookEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NegotiationStateUpdatedHandler implements NegotiatorEventHandler {

  private static final String EVENT_TYPE = "negotiation.state.updated";
  private static final String APPROVE_EVENT = "APPROVE";
  private static final String DEFAULT_INITIAL_STATE = "SUBMITTED";

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry registry;
  private final Map<HelpDeskType, HelpDeskAdapter> adapters;
  private final TicketMappingRepository ticketMappingRepository;
  private final ObjectMapper objectMapper;

  public NegotiationStateUpdatedHandler(
      NegotiatorClient negotiatorClient,
      OrganizationConfigRegistry registry,
      Map<HelpDeskType, HelpDeskAdapter> adapters,
      TicketMappingRepository ticketMappingRepository,
      ObjectMapper objectMapper) {
    this.negotiatorClient = negotiatorClient;
    this.registry = registry;
    this.adapters = adapters;
    this.ticketMappingRepository = ticketMappingRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public void handle(JsonNode data) {
    NegotiationStateUpdatedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationStateUpdatedWebhookEvent.class);

    if (event.getEvent() == null) {
      log.warn("negotiation.state.updated has no 'event' field — discarding");
      return;
    }

    if (APPROVE_EVENT.equals(event.getEvent().getValue())) {
      handleApprove(event);
    } else {
      handleStateChange(event);
    }
  }

  private void handleApprove(NegotiationStateUpdatedWebhookEvent event) {
    String negotiationId = event.getNegotiationId();
    log.info("Handling APPROVE for negotiation '{}'", negotiationId);

    // TODO: this need to be change to obtain the config from organization which is allowed to read
    // the negotiation
    //        and it is in registry. For now, we just take the first one since webhook does not
    // provide the
    //        organization externalId.
    OrganizationConfig fetchConfig = registry.findAll().stream().findFirst().orElse(null);
    if (fetchConfig == null) {
      log.warn("No organizations configured — cannot fetch negotiation '{}'", negotiationId);
      return;
    }

    EntityModelNegotiationDTO negotiation =
        negotiatorClient.getNegotiation(negotiationId, fetchConfig);
    List<EntityModelResourceWithStatusDTO> resources =
        negotiatorClient.getResources(negotiationId, fetchConfig);

    if (resources.isEmpty()) {
      log.warn("No resources found for negotiation '{}' — nothing to ticket", negotiationId);
      return;
    }

    groupByOrg(resources)
        .forEach((orgExtId, orgResources) -> ticketOrg(negotiation, orgExtId, orgResources));

    // TODO: fetch the comment already present in the negotiation and add it to the ticket(s) as an
    // external comment
  }

  /** Creates tickets for a single organization's resources, skipping/logging on any failure. */
  private void ticketOrg(
      EntityModelNegotiationDTO negotiation,
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> orgResources) {
    OrgTicketContext context = resolveOrgContext(negotiation.getId(), orgExtId);
    if (context == null) {
      return;
    }

    try {
      if (context.config().ticketStrategy() == TicketStrategy.PER_NEGOTIATION) {
        handlePerNegotiation(negotiation, orgResources, context);
      } else {
        handlePerResource(negotiation, orgResources, context);
      }
    } catch (Exception e) {
      log.error(
          "Failed to create ticket(s) for org '{}' in negotiation '{}'",
          orgExtId,
          negotiation.getId(),
          e);
    }
  }

  /** Resolves the org config and adapter, or returns {@code null} if unmanaged/misconfigured. */
  private OrgTicketContext resolveOrgContext(String negotiationId, String orgExtId) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.debug("Organization '{}' not in config — skipping", orgExtId);
      return null;
    }

    HelpDeskAdapter adapter = adapters.get(config.type());
    if (adapter == null) {
      log.error("No adapter for help desk type '{}' (org '{}')", config.type(), orgExtId);
      return null;
    }

    return new OrgTicketContext(negotiationId, orgExtId, config, adapter);
  }

  private void handleStateChange(NegotiationStateUpdatedWebhookEvent event) {
    // TODO: milestone 2 — update negotiation_state field on all mapped tickets
    log.debug(
        "Ignoring negotiation state change '{}' for negotiation '{}' — not yet implemented",
        event.getEvent(),
        event.getNegotiationId());
  }

  private void handlePerNegotiation(
      EntityModelNegotiationDTO negotiation,
      List<EntityModelResourceWithStatusDTO> resources,
      OrgTicketContext context) {

    String body =
        NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(negotiation, resources);
    String title = NegotiationTicketBodyFormatter.buildTitle(negotiation.getDisplayId(), null);
    CreateTicketRequest request =
        new CreateTicketRequest(
            title, negotiation.getStatus(), resolveInitialState(resources.getFirst()), body);

    CreatedTicket created = context.adapter().createTicket(request, context.config());
    log.info(
        "Created PER_NEGOTIATION ticket '{}' for org '{}' in negotiation '{}'",
        created.ticketId(),
        context.orgExtId(),
        context.negotiationId());

    resources.forEach(resource -> saveMapping(created, resource, context));
  }

  private void handlePerResource(
      EntityModelNegotiationDTO negotiation,
      List<EntityModelResourceWithStatusDTO> resources,
      OrgTicketContext context) {

    List<CreatedTicket> createdTickets = new ArrayList<>();

    for (EntityModelResourceWithStatusDTO resource : resources) {
      String body =
          NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource);
      String title =
          NegotiationTicketBodyFormatter.buildTitle(negotiation.getDisplayId(), resource.getName());
      CreateTicketRequest request =
          new CreateTicketRequest(
              title, negotiation.getStatus(), resolveInitialState(resource), body);

      CreatedTicket created = context.adapter().createTicket(request, context.config());
      log.info(
          "Created PER_RESOURCE ticket '{}' for resource '{}' in negotiation '{}'",
          created.ticketId(),
          resource.getId(),
          context.negotiationId());

      saveMapping(created, resource, context);
      createdTickets.add(created);
    }

    if (createdTickets.size() > 1) {
      context.adapter().linkRelatedTickets(createdTickets, context.config());
    }
  }

  /** Persists the mapping between a created ticket and a resource. */
  private void saveMapping(
      CreatedTicket ticket, EntityModelResourceWithStatusDTO resource, OrgTicketContext context) {
    TicketMappingId id =
        new TicketMappingId(
            context.negotiationId(), ticket.ticketId(), String.valueOf(resource.getId()));
    ticketMappingRepository.save(
        new TicketMapping(
            id,
            resource.getSourceId(),
            String.valueOf(resource.getOrganization().getId()),
            context.orgExtId()));
  }

  private String resolveInitialState(EntityModelResourceWithStatusDTO resource) {
    return resource.getCurrentState() != null
        ? resource.getCurrentState().getValue()
        : DEFAULT_INITIAL_STATE;
  }

  private Map<String, List<EntityModelResourceWithStatusDTO>> groupByOrg(
      List<EntityModelResourceWithStatusDTO> resources) {
    Map<String, List<EntityModelResourceWithStatusDTO>> result = new LinkedHashMap<>();
    for (EntityModelResourceWithStatusDTO resource : resources) {
      String extId =
          resource.getOrganization() != null ? resource.getOrganization().getExternalId() : null;
      if (extId == null) {
        log.warn("Resource '{}' has no organization externalId — skipping", resource.getId());
        continue;
      }
      result.computeIfAbsent(extId, k -> new ArrayList<>()).add(resource);
    }
    return result;
  }

  /** The resolved per-organization context for creating tickets during an APPROVE. */
  private record OrgTicketContext(
      String negotiationId, String orgExtId, OrganizationConfig config, HelpDeskAdapter adapter) {}
}
