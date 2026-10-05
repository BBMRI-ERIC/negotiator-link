package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.enumToText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.IntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreateTicketPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreateTicketPayload.ResourceMappingRef;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.LinkTicketsPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
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
      List<EntityModelPostDTO> posts) {}

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public List<PendingIntent> handle(JsonNode data) {
    NegotiationStateUpdatedWebhookEvent event = parseEvent(data);
    if (event == null) {
      return List.of();
    }

    if (APPROVE_EVENT.equals(event.getEvent().getValue())) {
      return handleApprove(event); // create tickets
    }
    return handleStateChange(event);
  }

  private List<PendingIntent> handleApprove(NegotiationStateUpdatedWebhookEvent event) {
    Optional<CreateNegotiationContext> contextOpt = buildCreateNegotiationContext(event);
    if (contextOpt.isEmpty()) {
      return List.of();
    }
    CreateNegotiationContext context = contextOpt.get();
    List<PendingIntent> intents = new ArrayList<>();
    groupByOrg(context.negotiationResources())
        .forEach(
            (orgExtId, orgResources) -> intents.addAll(ticketOrg(orgExtId, orgResources, context)));
    return intents;
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
    List<EntityModelPostDTO> posts = negotiatorClient.getPosts(negotiationId, result.config());
    return Optional.of(
        new CreateNegotiationContext(negotiation, resources, resolvedAttachments, posts));
  }

  /** Builds the CREATE_TICKET (+ LINK_TICKETS) intents for a single organization's resources. */
  private List<PendingIntent> ticketOrg(
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> orgResources,
      CreateNegotiationContext context) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.debug("Organization '{}' not in config — skipping", orgExtId);
      return List.of();
    }
    if (config.ticketStrategy() == TicketStrategy.PER_NEGOTIATION) {
      return handlePerNegotiation(orgExtId, orgResources, context);
    }
    return handlePerResource(orgExtId, orgResources, context);
  }

  private List<PendingIntent> handlePerNegotiation(
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> resources,
      CreateNegotiationContext context) {
    EntityModelNegotiationDTO negotiation = context.negotiation();
    String body =
        NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(negotiation, resources);
    String title = NegotiationTicketBodyFormatter.buildTitle(negotiation, null);

    CreateTicketPayload payload =
        new CreateTicketPayload(
            negotiation.getId(),
            title,
            negotiation.getStatus(),
            resolveInitialState(resources.getFirst()),
            body,
            attachmentIds(context),
            resourceRefs(resources),
            orderAndSignNegotiationPosts(context.posts()));
    return List.of(createTicketIntent(negotiation.getId(), orgExtId, payload));
  }

  private List<PendingIntent> handlePerResource(
      String orgExtId,
      List<EntityModelResourceWithStatusDTO> resources,
      CreateNegotiationContext context) {
    EntityModelNegotiationDTO negotiation = context.negotiation();
    List<PendingIntent> intents = new ArrayList<>();

    for (EntityModelResourceWithStatusDTO resource : resources) {
      String body =
          NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource);
      String title = NegotiationTicketBodyFormatter.buildTitle(negotiation, resource.getName());
      CreateTicketPayload payload =
          new CreateTicketPayload(
              negotiation.getId(),
              title,
              negotiation.getStatus(),
              resolveInitialState(resource),
              body,
              attachmentIds(context),
              resourceRefs(List.of(resource)),
              orderAndSignNegotiationPosts(context.posts()));
      intents.add(createTicketIntent(negotiation.getId(), orgExtId, payload));
    }

    if (resources.size() > 1) {
      List<String> resourceSourceIds =
          resources.stream().map(EntityModelResourceWithStatusDTO::getSourceId).toList();
      intents.add(
          new PendingIntent(
              IntentType.LINK_TICKETS,
              TargetKind.TICKET,
              null,
              negotiation.getId(),
              orgExtId,
              serialize(new LinkTicketsPayload(negotiation.getId(), resourceSourceIds))));
    }
    return intents;
  }

  private PendingIntent createTicketIntent(
      String negotiationId, String orgExtId, CreateTicketPayload payload) {
    return new PendingIntent(
        IntentType.CREATE_TICKET,
        TargetKind.TICKET,
        null,
        negotiationId,
        orgExtId,
        serialize(payload));
  }

  private List<String> attachmentIds(CreateNegotiationContext context) {
    return context.resolvedAttachments().stream().map(ResolvedAttachment::attachmentId).toList();
  }

  private List<ResourceMappingRef> resourceRefs(List<EntityModelResourceWithStatusDTO> resources) {
    return resources.stream()
        .map(
            dto ->
                new ResourceMappingRef(
                    String.valueOf(dto.getId()),
                    dto.getSourceId(),
                    String.valueOf(dto.getOrganization().getId())))
        .toList();
  }

  private List<String> orderAndSignNegotiationPosts(List<EntityModelPostDTO> posts) {
    return posts.stream()
        .filter(post -> post.getText() != null)
        .sorted(
            Comparator.comparing(
                EntityModelPostDTO::getCreationDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
        .map(this::signPostText)
        .toList();
  }

  private String signPostText(EntityModelPostDTO post) {
    UserResponseModel createdBy = post.getCreatedBy();
    Sender sender =
        createdBy != null ? new Sender(createdBy.getName(), createdBy.getEmail()) : null;
    return PostUtil.signPost(post.getText(), sender);
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

  private List<PendingIntent> handleStateChange(NegotiationStateUpdatedWebhookEvent event) {
    String negotiationId = event.getNegotiationId();
    String toState = event.getToState().getValue();

    Map<String, String> ticketToOrgExtId = getDistinctTicketIdToOrgExtId(negotiationId);
    if (ticketToOrgExtId.isEmpty()) {
      log.debug("No mapped tickets for negotiation '{}' — skipping state update", negotiationId);
      return List.of();
    }

    String signedPost = fetchSignedPost(negotiationId, event.getPostId(), ticketToOrgExtId);

    List<PendingIntent> intents = new ArrayList<>();
    ticketToOrgExtId.forEach(
        (ticketId, orgExtId) ->
            intents.addAll(
                stateChangeIntents(negotiationId, ticketId, orgExtId, toState, signedPost)));
    return intents;
  }

  private List<PendingIntent> stateChangeIntents(
      String negotiationId, String ticketId, String orgExtId, String toState, String signedPost) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.debug("Organization '{}' not in config — skipping", orgExtId);
      return List.of();
    }
    List<PendingIntent> intents = new ArrayList<>();
    intents.add(
        ticketIntent(
            IntentType.SET_NEGOTIATION_STATE,
            negotiationId,
            ticketId,
            orgExtId,
            serialize(new SetStatePayload(SetStatePayload.FIELD_NEGOTIATION_STATE, toState))));
    intents.add(
        ticketIntent(
            IntentType.ADD_SYSTEM_COMMENT,
            negotiationId,
            ticketId,
            orgExtId,
            serialize(
                new AddSystemCommentPayload(
                    "Negotiation status changed to '%s'".formatted(enumToText(toState))))));
    if (signedPost != null) {
      intents.add(
          ticketIntent(
              IntentType.ADD_COMMENT,
              negotiationId,
              ticketId,
              orgExtId,
              serialize(new AddCommentPayload(signedPost, CommentType.PLAIN_TEXT))));
    }
    String ticketState = config.negotiationStateToTicketState().get(toState);
    if (ticketState != null) {
      intents.add(
          ticketIntent(
              IntentType.SET_TICKET_STATE,
              negotiationId,
              ticketId,
              orgExtId,
              serialize(new SetStatePayload(SetStatePayload.FIELD_TICKET_STATE, ticketState))));
    }
    return intents;
  }

  private PendingIntent ticketIntent(
      IntentType type, String negotiationId, String ticketId, String orgExtId, String payload) {
    return new PendingIntent(type, TargetKind.TICKET, ticketId, negotiationId, orgExtId, payload);
  }

  private Map<String, String> getDistinctTicketIdToOrgExtId(String negotiationId) {
    Map<String, String> ticketToOrgExtId = new LinkedHashMap<>();
    for (TicketMapping m : ticketMappingRepository.findByIdNegotiationId(negotiationId)) {
      ticketToOrgExtId.putIfAbsent(m.getId().getHelpdeskTicketId(), m.getOrganizationExtId());
    }
    return ticketToOrgExtId;
  }

  /**
   * Fetches the post (if any) send along with state transition and returns its signed text, or
   * {@code null}.
   */
  private String fetchSignedPost(
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
    EntityModelPostDTO post = negotiatorClient.getPost(negotiationId, postId, config);
    if (post == null || post.getText() == null) {
      return null;
    }
    return signPostText(post);
  }

  /** Parses and validates the webhook event, or returns {@code null} if it is incomplete. */
  private NegotiationStateUpdatedWebhookEvent parseEvent(JsonNode data) {
    NegotiationStateUpdatedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationStateUpdatedWebhookEvent.class);

    String negotiationId = event.getNegotiationId();
    String toState = event.getToState() != null ? event.getToState().getValue() : null;
    String transitionEvent = event.getEvent() != null ? event.getEvent().getValue() : null;

    if (negotiationId == null || toState == null || transitionEvent == null) {
      log.warn("Incomplete state updated event — skipping");
      return null;
    }
    return event;
  }
}
