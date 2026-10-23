package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;

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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
  private final PendingTicketCreationCache pendingTicketCreationCache;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public OutboxIntentDispatcher(
      OrganizationConfigRegistry registry,
      java.util.Map<HelpdeskType, HelpdeskClient> clients,
      NegotiatorClient negotiatorClient,
      TicketMappingRepository ticketMappingRepository,
      OriginatedPostRepository originatedPostRepository,
      AttachmentForwardingService attachmentForwardingService,
      PendingTicketCreationCache pendingTicketCreationCache,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.registry = registry;
    this.clients = clients;
    this.negotiatorClient = negotiatorClient;
    this.ticketMappingRepository = ticketMappingRepository;
    this.originatedPostRepository = originatedPostRepository;
    this.attachmentForwardingService = attachmentForwardingService;
    this.pendingTicketCreationCache = pendingTicketCreationCache;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  public DispatchResult dispatch(OutboxIntent intent) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      return doDispatch(intent);
    } finally {
      sample.stop(
          Timer.builder("outbox_delivery_duration_seconds")
              .description("Latency of each outbound help-desk/Negotiator call, per intent type")
              .tag("intent_type", intent.getIntentType().name())
              .publishPercentileHistogram()
              .register(meterRegistry));
    }
  }

  private DispatchResult doDispatch(OutboxIntent intent) {
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

    return switch (intent.getIntentType()) {
      case CREATE_TICKET -> createTicket(intent, config, client);
      case LINK_TICKETS -> linkTickets(intent, config, client);
      case ADD_COMMENT -> addComment(intent, config, client);
      case ADD_SYSTEM_COMMENT -> addSystemComment(intent, config, client);
      case SET_NEGOTIATION_STATE -> {
        client.updateNegotiationState(intent.getTargetId(), readState(intent).value(), config);
        yield DispatchResult.empty();
      }
      case SET_RESOURCE_STATE -> {
        client.updateResourceState(intent.getTargetId(), readState(intent).value(), config);
        yield DispatchResult.empty();
      }
      case SET_TICKET_STATE -> {
        client.updateTicketState(intent.getTargetId(), readState(intent).value(), config);
        yield DispatchResult.empty();
      }
      case CREATE_POST -> createPost(intent, config);
      case SEND_RESOURCE_EVENT -> sendResourceEvent(intent, config);
      case SEND_BULK_RESOURCE_EVENT -> sendBulkResourceEvent(intent, config);
    };
  }

  private DispatchResult createTicket(
      OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    CreateTicketPayload payload = read(intent, CreateTicketPayload.class);

    List<ResolvedAttachment> resolved =
        attachmentForwardingService.getNegotiationPayloadAttachmentsToForward(
            payload.negotiationId(), payload.attachmentIds(), config, Set.of());
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

    List<TicketMapping> ticketMappings = new ArrayList<>();
    for (CreateTicketPayload.ResourceMappingRef ref : payload.resources()) {
      ticketMappings.add(
          new TicketMapping(
              new TicketMappingId(payload.negotiationId(), ticketId, ref.resourceId()),
              ref.resourceSourceId(),
              ref.resourceOrgId(),
              intent.getOrganizationExtId()));
    }

    List<PendingIntent> initialComments = new ArrayList<>();
    for (String postText : payload.postTexts()) {
      initialComments.add(
          new PendingIntent(
              OutboxIntentType.ADD_COMMENT,
              TargetKind.TICKET,
              ticketId,
              payload.negotiationId(),
              intent.getOrganizationExtId(),
              serialize(new AddCommentPayload(postText, CommentType.PLAIN_TEXT))));
    }

    DispatchResult.ForwardedAttachmentsRecord forwarded =
        new DispatchResult.ForwardedAttachmentsRecord(
            payload.negotiationId(),
            intent.getOrganizationExtId(),
            ticketId,
            resolved.stream().map(ResolvedAttachment::attachmentId).toList());
    return new DispatchResult(ticketMappings, Optional.of(forwarded), initialComments);
  }

  private DispatchResult linkTickets(
      OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    LinkTicketsPayload payload = read(intent, LinkTicketsPayload.class);
    List<String> ticketIds = new ArrayList<>();
    for (String resourceSourceId : payload.resourceSourceIds()) {
      ticketMappingRepository
          .findByIdNegotiationIdAndResourceSourceId(payload.negotiationId(), resourceSourceId)
          .ifPresent(mapping -> ticketIds.add(mapping.getId().getHelpdeskTicketId()));
    }
    if (pendingTicketCreationCache.isTracked(payload.negotiationId())) {
      throw new PendingTicketCreationException(payload.negotiationId());
    }
    if (ticketIds.size() > 1) {
      client.linkRelatedTickets(ticketIds, config);
    }
    return DispatchResult.empty();
  }

  private DispatchResult addComment(
      OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    JsonNode node = readTree(intent);
    if (node.hasNonNull("negotiationAttachmentIds")) {
      return addCommentWithNewAttachments(node, intent, config, client);
    }

    AddCommentPayload payload = convert(node, AddCommentPayload.class);
    client.addComment(intent.getTargetId(), payload.text(), payload.commentType(), config);
    return DispatchResult.empty();
  }

  private DispatchResult addSystemComment(
      OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    AddSystemCommentPayload payload = read(intent, AddSystemCommentPayload.class);
    client.addSystemComment(intent.getTargetId(), payload.message(), config);
    return DispatchResult.empty();
  }

  private DispatchResult createPost(OutboxIntent intent, OrganizationConfig config) {
    CreatePostPayload payload = read(intent, CreatePostPayload.class);
    String organizationExtId = config.organizationExternalId();

    String textHash = sha256Hex(payload.signedText());
    boolean alreadyConsumed =
        originatedPostRepository
            .findFirstByTextHashAndOrganizationExtIdAndTicketIdOrderByCreatedAtAsc(
                textHash, organizationExtId, payload.ticketId())
            .isEmpty();
    if (alreadyConsumed) {
      log.info(
          "originated_post for negotiation '{}' already consumed by echo — post already created, skipping",
          payload.negotiationId());
      return DispatchResult.empty();
    }
    PostCreateDTO post =
        new PostCreateDTO()
            .text(payload.signedText())
            .organizationId(organizationExtId)
            .type(PostCreateDTO.TypeEnum.PRIVATE);
    negotiatorClient.createPost(payload.negotiationId(), post, config);
    log.info(
        "Forwarded help desk post to negotiation '{}' as PRIVATE post", payload.negotiationId());
    return DispatchResult.empty();
  }

  private DispatchResult sendResourceEvent(OutboxIntent intent, OrganizationConfig config) {
    SendResourceEventPayload payload = read(intent, SendResourceEventPayload.class);
    negotiatorClient.sendResourceEvent(
        payload.negotiationId(), payload.resourceSourceId(), payload.event(), config);
    return DispatchResult.empty();
  }

  private DispatchResult sendBulkResourceEvent(OutboxIntent intent, OrganizationConfig config) {
    SendBulkResourceEventPayload payload = read(intent, SendBulkResourceEventPayload.class);
    negotiatorClient.sendBulkResourceEvent(
        payload.negotiationId(), payload.resourceIds(), payload.targetState(), config);
    return DispatchResult.empty();
  }

  private DispatchResult addCommentWithNewAttachments(
      JsonNode node, OutboxIntent intent, OrganizationConfig config, HelpdeskClient client) {
    AddNegotiationAttachmentPayload payload = convert(node, AddNegotiationAttachmentPayload.class);
    Set<String> alreadyForwarded =
        attachmentForwardingService.alreadyForwardedIds(
            intent.getOrganizationExtId(), intent.getTargetId());
    List<ResolvedAttachment> resolved =
        attachmentForwardingService.getNegotiationPayloadAttachmentsToForward(
            payload.negotiationId(), payload.negotiationAttachmentIds(), config, alreadyForwarded);
    client.addComment(
        intent.getTargetId(),
        payload.text(),
        CommentType.PLAIN_TEXT,
        resolved.stream().map(ResolvedAttachment::attachment).toList(),
        config);
    DispatchResult.ForwardedAttachmentsRecord forwarded =
        new DispatchResult.ForwardedAttachmentsRecord(
            payload.negotiationId(),
            intent.getOrganizationExtId(),
            intent.getTargetId(),
            resolved.stream().map(ResolvedAttachment::attachmentId).toList());
    return new DispatchResult(List.of(), Optional.of(forwarded));
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
