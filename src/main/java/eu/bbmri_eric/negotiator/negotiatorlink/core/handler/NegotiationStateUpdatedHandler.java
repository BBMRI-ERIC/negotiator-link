package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.enumToText;

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
import java.util.Optional;
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

  /**
   * Negotiation-wide data shared by every ticket created for the event, resolved once per event.
   */
  private record CreateNegotiationContext(
      EntityModelNegotiationDTO negotiation,
      List<EntityModelResourceWithStatusDTO> negotiationResources,
      List<ResolvedAttachment> resolvedAttachments,
      Attachment pdfSummary) {}

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
    buildCreateNegotiationContext(event)
        .ifPresent(
            context ->
                groupByOrg(context.negotiationResources())
                    .forEach(
                        (orgExtId, orgResources) -> ticketOrg(orgExtId, orgResources, context)));
  }

  private Optional<CreateNegotiationContext> buildCreateNegotiationContext(
      NegotiationStateUpdatedWebhookEvent event) {
    String negotiationId = event.getNegotiationId();
    log.info("Handling APPROVE for negotiation '{}'", negotiationId);

    NegotiationWithConfig result = fetchNegotiationFromAuthorizedOrg(negotiationId);
    if (result == null) {
      log.warn("No authorized organization found for negotiation '{}' — skipping", negotiationId);
      return Optional.empty();
    }

    EntityModelNegotiationDTO negotiation = result.negotiation();
    List<EntityModelResourceWithStatusDTO> resources =
        negotiatorClient.getResources(negotiationId, result.config());
    List<ResolvedAttachment> resolvedAttachments =
        attachmentForwardingService.resolveForwardablePayloadAttachments(
            negotiationId, result.config());
    Attachment pdfSummary =
        attachmentForwardingService.getNegotiationPdfSummary(negotiationId, result.config());
    return Optional.of(
        new CreateNegotiationContext(negotiation, resources, resolvedAttachments, pdfSummary));
  }

  /** Creates tickets for a single organization's resources, skipping/logging on any failure. */
  private void ticketOrg(
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> orgResources,
      CreateNegotiationContext context) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.debug("Organization '{}' not in config — skipping", orgExtId);
      return;
    }
    HelpdeskClient client = clients.get(config.type());

    try {
      if (config.ticketStrategy() == TicketStrategy.PER_NEGOTIATION) {
        handlePerNegotiation(orgExtId, orgResources, config, client, context);
      } else {
        handlePerResource(orgExtId, orgResources, config, client, context);
      }
    } catch (Exception e) {
      log.error(
          "Failed to create ticket(s) for org '{}' in negotiation '{}'",
          orgExtId,
          context.negotiation().getId(),
          e);
    }
  }

  private void handlePerNegotiation(
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> resources,
      OrganizationConfig config,
      HelpdeskClient client,
      CreateNegotiationContext context) {
    EntityModelNegotiationDTO negotiation = context.negotiation();

    String body =
        NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(negotiation, resources);
    String title = NegotiationTicketBodyFormatter.buildTitle(negotiation, null);

    CreateTicketRequest request =
        new CreateTicketRequest(
            title,
            negotiation.getStatus(),
            resolveInitialState(resources.getFirst()),
            body,
            ticketFiles(context));

    CreatedTicket created = client.createTicket(request, config);
    log.info(
        "Created PER_NEGOTIATION ticket '{}' for org '{}' in negotiation '{}'",
        created.ticketId(),
        orgExtId,
        negotiation.getId());
    List<EntityModelPostDTO> posts = negotiatorClient.getPosts(negotiation.getId(), config);
    afterTicketCreated(created, resources, posts, orgExtId, config, client, context);
  }

  private void handlePerResource(
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> resources,
      OrganizationConfig config,
      HelpdeskClient client,
      CreateNegotiationContext context) {
    EntityModelNegotiationDTO negotiation = context.negotiation();
    List<EntityModelPostDTO> posts = negotiatorClient.getPosts(negotiation.getId(), config);

    List<CreatedTicket> createdTickets = new ArrayList<>();
    for (EntityModelResourceWithStatusDTO resource : resources) {
      String body =
          NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource);
      String title = NegotiationTicketBodyFormatter.buildTitle(negotiation, resource.getName());

      CreateTicketRequest request =
          new CreateTicketRequest(
              title,
              negotiation.getStatus(),
              resolveInitialState(resource),
              body,
              ticketFiles(context));

      CreatedTicket created = client.createTicket(request, config);
      log.info(
          "Created PER_RESOURCE ticket '{}' for resource '{}' in negotiation '{}'",
          created.ticketId(),
          resource.getId(),
          negotiation.getId());
      afterTicketCreated(created, List.of(resource), posts, orgExtId, config, client, context);
      createdTickets.add(created);
    }

    if (createdTickets.size() > 1) {
      client.linkRelatedTickets(createdTickets, config);
    }
  }

  private void afterTicketCreated(
      CreatedTicket createdTicket,
      List<EntityModelResourceWithStatusDTO> ticketResources,
      List<EntityModelPostDTO> posts,
      String orgExtId,
      OrganizationConfig config,
      HelpdeskClient client,
      CreateNegotiationContext context) {
    String negotiationId = context.negotiation().getId();
    attachmentForwardingService.recordForwardedAttachments(
        negotiationId,
        orgExtId,
        createdTicket.ticketId(),
        context.resolvedAttachments().stream().map(ResolvedAttachment::attachmentId).toList());
    ticketResources.forEach(
        resource -> saveMapping(createdTicket, resource, negotiationId, orgExtId));
    postCommentsToTicket(createdTicket.ticketId(), posts, config, client);
  }

  private void postCommentsToTicket(
      String ticketId,
      List<EntityModelPostDTO> posts,
      OrganizationConfig config,
      HelpdeskClient client) {
    if (posts.isEmpty()) {
      return;
    }
    posts.stream()
        .filter(post -> post.getText() != null)
        .sorted(
            Comparator.comparing(
                EntityModelPostDTO::getCreationDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
        .forEach(post -> addPostAsComment(ticketId, post, config, client));
  }

  private void saveMapping(
      CreatedTicket ticket,
      EntityModelResourceWithStatusDTO resource,
      String negotiationId,
      String orgExtId) {
    TicketMappingId id =
        new TicketMappingId(negotiationId, ticket.ticketId(), String.valueOf(resource.getId()));
    ticketMappingRepository.save(
        new TicketMapping(
            id,
            resource.getSourceId(),
            String.valueOf(resource.getOrganization().getId()),
            orgExtId));
  }

  /**
   * The files attached to a new ticket: the forwardable payload attachments plus the PDF summary.
   */
  private List<Attachment> ticketFiles(CreateNegotiationContext context) {
    List<Attachment> files =
        new ArrayList<>(
            context.resolvedAttachments().stream().map(ResolvedAttachment::attachment).toList());
    files.add(context.pdfSummary());
    return files;
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

    EntityModelPostDTO post = fetchPost(negotiationId, event.getPostId(), ticketToOrgExtId);

    ticketToOrgExtId.forEach(
        (ticketId, orgExtId) -> updateNegotiationStateInTicket(ticketId, orgExtId, toState, post));
  }

  private Map<String, String> getDistinctTicketIdToOrgExtId(String negotiationId) {
    Map<String, String> ticketToOrgExtId = new LinkedHashMap<>();
    for (TicketMapping m : ticketMappingRepository.findByIdNegotiationId(negotiationId)) {
      ticketToOrgExtId.putIfAbsent(m.getId().getHelpdeskTicketId(), m.getOrganizationExtId());
    }
    return ticketToOrgExtId;
  }

  private void updateNegotiationStateInTicket(
      String ticketId, String orgExtId, String toState, EntityModelPostDTO post) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.debug("Organization '{}' not in config — skipping", orgExtId);
      return;
    }
    HelpdeskClient client = clients.get(config.type());
    try {
      log.info("Updating negotiation state on ticket '{}' to state '{}'", ticketId, toState);
      client.updateNegotiationState(ticketId, toState, config);
      client.addSystemComment(
          ticketId, "Negotiation status changed to '%s'".formatted(enumToText(toState)), config);
      addPostAsComment(ticketId, post, config, client);

      String ticketState = config.negotiationStateToTicketState().get(toState);
      if (ticketState != null) {
        client.updateTicketState(ticketId, ticketState, config);
      }
    } catch (Exception e) {
      log.error("Fail in updating negotiation state on ticket '{}'", ticketId, e);
    }
  }

  private EntityModelPostDTO fetchPost(
      String negotiationId, String postId, Map<String, String> ticketToOrgExtId) {
    if (postId == null) {
      return null;
    }
    String orgExtId = ticketToOrgExtId.values().stream().findFirst().orElseThrow();
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.debug("Organization '{}' not in config — skipping", orgExtId);
      return null;
    }
    try {
      return negotiatorClient.getPost(negotiationId, postId, config);
    } catch (Exception e) {
      log.error(
          "Failed to fetch post '{}' while updating the state of negotiation '{}'",
          postId,
          negotiationId,
          e);
      return null;
    }
  }

  /** Posts the given post as a comment on the ticket, if present. */
  private void addPostAsComment(
      String ticketId, EntityModelPostDTO post, OrganizationConfig config, HelpdeskClient client) {
    if (post == null || post.getText() == null) {
      return;
    }
    UserResponseModel createdBy = post.getCreatedBy();
    String postText = post.getText();
    Sender sender =
        createdBy != null ? new Sender(createdBy.getName(), createdBy.getEmail()) : null;
    String signedText = PostUtil.signPost(postText, sender);
    client.addComment(ticketId, signedText, CommentType.PLAIN_TEXT, config);
    log.info("Posted comment '{}' to ticket '{}'", post.getId(), ticketId);
  }
}
