package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddNegotiationAttachmentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreatePostPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreateTicketPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.LinkTicketsPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendBulkResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService.ResolvedAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** The single place that maps an {@link OutboxIntentType} to a concrete client write call. */
@Slf4j
@Component
public class OutboxIntentDispatcher {

  private final OrganizationConfigRegistry registry;
  private final java.util.Map<HelpdeskType, HelpdeskClient> clients;
  private final NegotiatorClient negotiatorClient;
  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedPostRepository originatedPostRepository;
  private final AttachmentForwardingService attachmentForwardingService;
  private final ObjectMapper objectMapper;

  public OutboxIntentDispatcher(
      OrganizationConfigRegistry registry,
      java.util.Map<HelpdeskType, HelpdeskClient> clients,
      NegotiatorClient negotiatorClient,
      TicketMappingRepository ticketMappingRepository,
      OriginatedPostRepository originatedPostRepository,
      AttachmentForwardingService attachmentForwardingService,
      ObjectMapper objectMapper) {
    this.registry = registry;
    this.clients = clients;
    this.negotiatorClient = negotiatorClient;
    this.ticketMappingRepository = ticketMappingRepository;
    this.originatedPostRepository = originatedPostRepository;
    this.attachmentForwardingService = attachmentForwardingService;
    this.objectMapper = objectMapper;
  }

  public void dispatch(OutboxIntent intent) {
    OrganizationConfig config =
        registry
            .findByExternalId(intent.getOrganizationExtId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Organization '"
                            + intent.getOrganizationExtId()
                            + "' no longer configured — cannot deliver intent "
                            + intent.getRowId()));
    HelpdeskClient client = clients.get(config.type());

    switch (intent.getIntentType()) {
      case CREATE_TICKET -> createTicket(intent, config, client);
      case LINK_TICKETS -> linkTickets(intent, config, client);
      case ADD_COMMENT -> addComment(intent, config, client);
      case ADD_SYSTEM_COMMENT -> addSystemComment(intent, config, client);
      case SET_NEGOTIATION_STATE ->
          client.updateNegotiationState(intent.getTargetId(), readState(intent).value(), config);
      case SET_RESOURCE_STATE ->
          client.updateResourceState(intent.getTargetId(), readState(intent).value(), config);
      case SET_TICKET_STATE ->
          client.updateTicketState(intent.getTargetId(), readState(intent).value(), config);
      case CREATE_POST -> createPost(intent, config);
      case SEND_RESOURCE_EVENT -> sendResourceEvent(intent, config);
      case SEND_BULK_RESOURCE_EVENT -> sendBulkResourceEvent(intent, config);
    }
  }

  private void createTicket(OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    CreateTicketPayload payload = read(intent, CreateTicketPayload.class);

    List<ResolvedAttachment> resolved =
        attachmentForwardingService.resolveForwardablePayloadAttachments(
            payload.negotiationId(), config);
    List<Attachment> attachments = resolved.stream().map(ResolvedAttachment::attachment).toList();

    CreateTicketRequest request =
        new CreateTicketRequest(
            payload.title(),
            payload.negotiationState(),
            payload.resourceState(),
            payload.ticketBody(),
            attachments);
    String ticketId = client.createTicket(request, config);
    log.info(
        "Created ticket '{}' for negotiation '{}' org '{}'",
        ticketId,
        payload.negotiationId(),
        intent.getOrganizationExtId());

    for (CreateTicketPayload.ResourceMappingRef ref : payload.resources()) {
      ticketMappingRepository.save(
          new TicketMapping(
              new TicketMappingId(payload.negotiationId(), ticketId, ref.resourceId()),
              ref.resourceSourceId(),
              ref.resourceOrgId(),
              intent.getOrganizationExtId()));
    }

    attachmentForwardingService.recordForwardedAttachments(
        payload.negotiationId(),
        intent.getOrganizationExtId(),
        ticketId,
        resolved.stream().map(ResolvedAttachment::attachmentId).toList());

    for (String postText : payload.postTexts()) {
      client.addComment(ticketId, postText, CommentType.PLAIN_TEXT, config);
    }
  }

  private void linkTickets(OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    LinkTicketsPayload payload = read(intent, LinkTicketsPayload.class);
    List<String> ticketIds = new ArrayList<>();
    for (String resourceSourceId : payload.resourceSourceIds()) {
      ticketMappingRepository
          .findByIdNegotiationIdAndResourceSourceId(payload.negotiationId(), resourceSourceId)
          .ifPresent(mapping -> ticketIds.add(mapping.getId().getHelpdeskTicketId()));
    }
    if (ticketIds.size() > 1) {
      client.linkRelatedTickets(ticketIds, config);
    }
  }

  private void addComment(OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    JsonNode node = readTree(intent);
    if (node.hasNonNull("negotiationAttachmentIds")) {
      addCommentWithNewAttachments(node, intent, config, client);
      return;
    }

    AddCommentPayload payload = convert(node, AddCommentPayload.class);
    client.addComment(intent.getTargetId(), payload.text(), payload.commentType(), config);
  }

  private void addSystemComment(
      OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    AddSystemCommentPayload payload = read(intent, AddSystemCommentPayload.class);
    client.addSystemComment(intent.getTargetId(), payload.message(), config);
  }

  private void createPost(OutboxIntent intent, OrganizationConfig config) {
    CreatePostPayload payload = read(intent, CreatePostPayload.class);
    String organizationExtId = config.organizationExternalId();
    String organizationId =
        getOrganizationIdFromTicketMapping(
            payload.ticketId(), organizationExtId, intent.getRowId());

    boolean alreadyConsumed =
        originatedPostRepository
            .findFirstByTicketIdAndOrganizationId(payload.ticketId(), organizationId)
            .isEmpty();
    if (alreadyConsumed) {
      log.info(
          "originated_post for negotiation '{}' already consumed by echo — post already created, skipping",
          payload.negotiationId());
      return;
    }
    PostCreateDTO post =
        new PostCreateDTO()
            .text(payload.signedText())
            .organizationId(organizationExtId)
            .type(PostCreateDTO.TypeEnum.PRIVATE);
    negotiatorClient.createPost(payload.negotiationId(), post, config);
    log.info(
        "Forwarded help desk post to negotiation '{}' as PRIVATE post", payload.negotiationId());
  }

  private void sendResourceEvent(OutboxIntent intent, OrganizationConfig config) {
    SendResourceEventPayload payload = read(intent, SendResourceEventPayload.class);
    negotiatorClient.sendResourceEvent(
        payload.negotiationId(), payload.resourceSourceId(), payload.event(), config);
  }

  private void sendBulkResourceEvent(OutboxIntent intent, OrganizationConfig config) {
    SendBulkResourceEventPayload payload = read(intent, SendBulkResourceEventPayload.class);
    negotiatorClient.sendBulkResourceEvent(
        payload.negotiationId(), payload.resourceIds(), payload.targetState(), config);
  }

  private void addCommentWithNewAttachments(
      JsonNode node, OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    AddNegotiationAttachmentPayload payload = convert(node, AddNegotiationAttachmentPayload.class);
    List<ResolvedAttachment> resolved =
        attachmentForwardingService.notYetForwardedToTicket(
            intent.getOrganizationExtId(),
            intent.getTargetId(),
            attachmentForwardingService.resolveForwardablePayloadAttachments(
                payload.negotiationId(), config));
    client.addComment(
        intent.getTargetId(),
        payload.text(),
        CommentType.PLAIN_TEXT,
        resolved.stream().map(ResolvedAttachment::attachment).toList(),
        config);
    attachmentForwardingService.recordForwardedAttachments(
        payload.negotiationId(),
        intent.getOrganizationExtId(),
        intent.getTargetId(),
        resolved.stream().map(ResolvedAttachment::attachmentId).toList());
  }

  private String getOrganizationIdFromTicketMapping(
      String ticketId, String organizationExtId, Long rowId) {
    return ticketMappingRepository
        .findByIdHelpdeskTicketIdAndOrganizationExtId(ticketId, organizationExtId)
        .stream()
        .findFirst()
        .map(TicketMapping::getOrganizationId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No ticket mapping for ticket '%s' org '%s' — cannot deliver intent %s"
                        .formatted(ticketId, organizationExtId, rowId)));
  }

  private SetStatePayload readState(OutboxIntent intent) {
    return read(intent, SetStatePayload.class);
  }

  private <T> T read(OutboxIntent intent, Class<T> type) {
    try {
      return objectMapper.readValue(intent.getPayload(), type);
    } catch (IOException e) {
      throw new IllegalStateException("Unreadable payload for intent " + intent.getRowId(), e);
    }
  }

  private JsonNode readTree(OutboxIntent intent) {
    try {
      return objectMapper.readTree(intent.getPayload());
    } catch (IOException e) {
      throw new IllegalStateException("Unreadable payload for intent " + intent.getRowId(), e);
    }
  }

  private <T> T convert(JsonNode node, Class<T> type) {
    return objectMapper.convertValue(node, type);
  }
}
