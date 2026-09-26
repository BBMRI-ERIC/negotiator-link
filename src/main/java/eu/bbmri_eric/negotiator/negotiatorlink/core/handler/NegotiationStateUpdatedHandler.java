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
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService.ResolvedAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationStateUpdatedWebhookEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class NegotiationStateUpdatedHandler implements NegotiationEventHandler {

  private static final String EVENT_TYPE = "negotiation.state.updated";
  private static final String APPROVE_EVENT = "APPROVE";
  private static final String DEFAULT_INITIAL_STATE = "SUBMITTED";

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry registry;
  private final Map<HelpdeskType, HelpdeskClient> clients;
  private final TicketMappingRepository ticketMappingRepository;
  private final AttachmentForwardingService attachmentForwardingService;
  private final ObjectMapper objectMapper;

  private record NegotiationWithConfig(
      EntityModelNegotiationDTO negotiation, OrganizationConfig config) {}

  /** The resolved per-organization context for creating tickets */
  private record OrgTicketContext(
      String negotiationId, String orgExtId, OrganizationConfig config, HelpdeskClient client) {}

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

    NegotiationWithConfig result = fetchNegotiationFromAuthorizedOrg(negotiationId);
    if (result == null) {
      log.warn("No authorized organization found for negotiation '{}' — skipping", negotiationId);
      return;
    }

    EntityModelNegotiationDTO negotiation = result.negotiation();
    List<EntityModelResourceWithStatusDTO> resources =
        negotiatorClient.getResources(negotiationId, result.config());

    if (resources.isEmpty()) {
      log.warn("No resources found for negotiation '{}' — nothing to ticket", negotiationId);
      return;
    }

    List<ResolvedAttachment> attachments =
        attachmentForwardingService.resolveNegotiationPayloadAttachmentsToForward(
            negotiationId, result.config());
    groupByOrg(resources)
        .forEach(
            (orgExtId, orgResources) ->
                ticketOrg(negotiation, orgExtId, orgResources, resources, attachments));

    if (!attachments.isEmpty()) {
      attachmentForwardingService.recordForwarded(
          negotiationId, attachments.stream().map(ResolvedAttachment::attachmentId).toList());
    }
  }

  /** Creates tickets for a single organization's resources, skipping/logging on any failure. */
  private void ticketOrg(
      EntityModelNegotiationDTO negotiation,
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> orgResources,
      List<EntityModelResourceWithStatusDTO> allResources,
      List<ResolvedAttachment> attachments) {
    OrgTicketContext context = resolveOrgContext(negotiation.getId(), orgExtId);
    if (context == null) {
      return;
    }

    try {
      if (context.config().ticketStrategy() == TicketStrategy.PER_NEGOTIATION) {
        handlePerNegotiation(negotiation, orgResources, context, allResources, attachments);
      } else {
        handlePerResource(negotiation, orgResources, context, allResources, attachments);
      }
    } catch (Exception e) {
      log.error(
          "Failed to create ticket(s) for org '{}' in negotiation '{}'",
          orgExtId,
          negotiation.getId(),
          e);
    }
  }

  private OrgTicketContext resolveOrgContext(String negotiationId, String orgExtId) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.debug("Organization '{}' not in config — skipping", orgExtId);
      return null;
    }

    HelpdeskClient client = clients.get(config.type());
    return new OrgTicketContext(negotiationId, orgExtId, config, client);
  }

  private void handlePerNegotiation(
      EntityModelNegotiationDTO negotiation,
      List<EntityModelResourceWithStatusDTO> resources,
      OrgTicketContext context,
      List<EntityModelResourceWithStatusDTO> allResources,
      List<ResolvedAttachment> attachments) {

    String body =
        NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(
            negotiation, resources, allResources);
    String title = NegotiationTicketBodyFormatter.buildTitle(negotiation, null);

    CreateTicketRequest request =
        new CreateTicketRequest(
            title,
            negotiation.getStatus(),
            resolveInitialState(resources.getFirst()),
            body,
            toAttachments(attachments));

    CreatedTicket created = context.client().createTicket(request, context.config());
    log.info(
        "Created PER_NEGOTIATION ticket '{}' for org '{}' in negotiation '{}'",
        created.ticketId(),
        context.orgExtId(),
        context.negotiationId());
    resources.forEach(resource -> saveMapping(created, resource, context));
    postNegotiationCommentsToTicket(created.ticketId(), context);
  }

  private void handlePerResource(
      EntityModelNegotiationDTO negotiation,
      List<EntityModelResourceWithStatusDTO> resources,
      OrgTicketContext context,
      List<EntityModelResourceWithStatusDTO> allResources,
      List<ResolvedAttachment> attachments) {

    List<CreatedTicket> createdTickets = new ArrayList<>();

    List<EntityModelPostDTO> posts =
        negotiatorClient.getPosts(context.negotiationId(), context.config());

    for (EntityModelResourceWithStatusDTO resource : resources) {
      String body =
          NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
              negotiation, resource, allResources);

      String title = NegotiationTicketBodyFormatter.buildTitle(negotiation, resource.getName());

      CreateTicketRequest request =
          new CreateTicketRequest(
              title,
              negotiation.getStatus(),
              resolveInitialState(resource),
              body,
              toAttachments(attachments));

      CreatedTicket created = context.client().createTicket(request, context.config());
      log.info(
          "Created PER_RESOURCE ticket '{}' for resource '{}' in negotiation '{}'",
          created.ticketId(),
          resource.getId(),
          context.negotiationId());
      saveMapping(created, resource, context);
      createdTickets.add(created);

      postCommentsToTicket(created.ticketId(), posts, context);
    }

    if (createdTickets.size() > 1) {
      context.client().linkRelatedTickets(createdTickets, context.config());
    }
  }

  private void postNegotiationCommentsToTicket(String ticketId, OrgTicketContext context) {
    List<EntityModelPostDTO> posts =
        negotiatorClient.getPosts(context.negotiationId(), context.config());
    postCommentsToTicket(ticketId, posts, context);
  }

  private void postCommentsToTicket(
      String ticketId, List<EntityModelPostDTO> posts, OrgTicketContext context) {
    OrganizationConfig config = context.config();
    if (posts.isEmpty()) {
      return;
    }
    posts.stream()
        .filter(post -> post.getText() != null)
        .sorted(
            Comparator.comparing(
                EntityModelPostDTO::getCreationDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
        .forEach(
            post -> {
              UserResponseModel createdBy = post.getCreatedBy();
              Sender sender =
                  createdBy != null ? new Sender(createdBy.getName(), createdBy.getEmail()) : null;
              String signedText = PostUtil.signPost(post.getText(), sender);
              try {
                context.client().addComment(ticketId, signedText, CommentType.PLAIN_TEXT, config);
                log.info("Posted comment '{}' to ticket '{}'", post.getId(), ticketId);
              } catch (Exception e) {
                log.error("Failed to post comment '{}' to ticket '{}'", post.getId(), ticketId, e);
              }
            });
  }

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

  private List<Attachment> toAttachments(List<ResolvedAttachment> resolved) {
    return resolved.stream().map(ResolvedAttachment::attachment).toList();
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

  /**
   * Finds an organization authorized to read the negotiation by probing each configured org's
   * credentials in turn until one is not rejected with 403. This should be improved in the future.
   */
  private NegotiationWithConfig fetchNegotiationFromAuthorizedOrg(String negotiationId) {
    for (OrganizationConfig config : registry.findAll()) {
      try {
        EntityModelNegotiationDTO negotiation =
            negotiatorClient.getNegotiation(negotiationId, config);
        log.debug(
            "Fetched negotiation '{}' using org '{}'",
            negotiationId,
            config.organizationExternalId());
        return new NegotiationWithConfig(negotiation, config);
      } catch (RestClientResponseException e) {
        if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
          log.debug(
              "Org '{}' not authorized for negotiation '{}' — trying next",
              config.organizationExternalId(),
              negotiationId);
        } else {
          throw e;
        }
      }
    }
    return null;
  }

  private void handleStateChange(NegotiationStateUpdatedWebhookEvent event) {
    String negotiationId = event.getNegotiationId();
    String toState = event.getToState() != null ? event.getToState().getValue() : null;

    if (negotiationId == null || toState == null) {
      log.warn("negotiation.state.updated missing negotiationId or toState — discarding");
      return;
    }

    Map<String, String> ticketToOrgExtId = getDistinctTicketIdToOrgExtId(negotiationId);
    if (ticketToOrgExtId.isEmpty()) {
      log.debug("No mapped tickets for negotiation '{}' — skipping state update", negotiationId);
      return;
    }

    ticketToOrgExtId.forEach(
        (ticketId, orgExtId) ->
            updateNegotiationStateInTicket(negotiationId, ticketId, orgExtId, toState));
  }

  private Map<String, String> getDistinctTicketIdToOrgExtId(String negotiationId) {
    Map<String, String> ticketToOrgExtId = new LinkedHashMap<>();
    for (TicketMapping m : ticketMappingRepository.findByIdNegotiationId(negotiationId)) {
      ticketToOrgExtId.putIfAbsent(m.getId().getHelpdeskTicketId(), m.getOrganizationExtId());
    }
    return ticketToOrgExtId;
  }

  private void updateNegotiationStateInTicket(
      String negotiationId, String ticketId, String orgExtId, String toState) {
    OrgTicketContext context = resolveOrgContext(negotiationId, orgExtId);
    if (context == null) {
      return;
    }
    try {
      log.info("Updating negotiation state on ticket '{}' to state '{}'", ticketId, toState);
      HelpdeskClient client = context.client();
      OrganizationConfig config = context.config();
      client.updateNegotiationState(ticketId, toState, context.config());

      String ticketState = config.negotiationStateToTicketState().get(toState);
      if (ticketState == null) {
        return;
      }
      client.updateTicketState(ticketId, ticketState, config);
    } catch (Exception e) {
      log.error("fail in updating negotiation state on ticket '{}'", ticketId, e);
    }
  }
}
