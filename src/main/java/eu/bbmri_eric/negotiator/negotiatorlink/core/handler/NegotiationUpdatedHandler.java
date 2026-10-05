package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddNegotiationAttachmentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationUpdatedWebhookEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
  private static final String ATTACHMENT_COMMENT_TEXT =
      "New negotiation-level attachments added by requestor. See the files below.";

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry registry;
  private final TicketMappingRepository ticketMappingRepository;
  private final AttachmentForwardingService attachmentForwardingService;
  private final ObjectMapper objectMapper;

  /** Negotiation-wide data shared by every updated ticket, resolved once per event. */
  private record NegotiationContext(
      String negotiationId,
      EntityModelNegotiationDTO negotiation,
      Map<String, EntityModelResourceWithStatusDTO> resourceById,
      List<TicketMapping> allMappings,
      List<String> candidateAttachmentIds) {}

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public List<PendingIntent> handle(JsonNode data) {
    NegotiationUpdatedWebhookEvent event = parseEvent(data);
    if (event == null) {
      return List.of();
    }

    String negotiationId = event.getNegotiationId();
    List<TicketMapping> mappings = ticketMappingRepository.findByIdNegotiationId(negotiationId);
    if (mappings.isEmpty()) {
      log.debug("No mapped tickets for negotiation '{}' — skipping update comment", negotiationId);
      return List.of();
    }

    OrganizationConfig fetchConfig =
        registry.findByExternalId(mappings.getFirst().getOrganizationExtId()).orElseThrow();

    NegotiationContext context = buildContext(negotiationId, mappings, fetchConfig);

    List<PendingIntent> intents = new ArrayList<>();
    getDistinctTicketIdToOrgExtId(mappings)
        .forEach(
            (ticketId, orgExtId) -> intents.addAll(processTicket(ticketId, orgExtId, context)));
    return intents;
  }

  /** Parses and validates the webhook event, or returns {@code null} if it is incomplete. */
  private NegotiationUpdatedWebhookEvent parseEvent(JsonNode data) {
    NegotiationUpdatedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationUpdatedWebhookEvent.class);

    String negotiationId = event.getNegotiationId();

    if (negotiationId == null) {
      log.warn("Incomplete negotiation updated event — skipping");
      return null;
    }
    return event;
  }

  private NegotiationContext buildContext(
      String negotiationId, List<TicketMapping> mappings, OrganizationConfig config) {
    EntityModelNegotiationDTO negotiation = negotiatorClient.getNegotiation(negotiationId, config);
    Map<String, EntityModelResourceWithStatusDTO> resourceById =
        negotiatorClient.getResources(negotiationId, config).stream()
            .collect(
                Collectors.toMap(
                    resource -> String.valueOf(resource.getId()),
                    Function.identity(),
                    (existing, duplicate) -> existing));
    List<String> candidateAttachmentIds =
        attachmentForwardingService.getNegotiationPayloadAttachmentsIds(negotiationId, config);
    return new NegotiationContext(
        negotiationId, negotiation, resourceById, mappings, candidateAttachmentIds);
  }

  private List<PendingIntent> processTicket(
      String ticketId, String orgExtId, NegotiationContext context) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.warn("Org '{}' not in config for ticket '{}' — skipping", orgExtId, ticketId);
      return List.of();
    }
    List<PendingIntent> intents = new ArrayList<>();
    intents.add(updateCommentIntent(ticketId, orgExtId, config, context));
    attachmentIntent(ticketId, orgExtId, context).ifPresent(intents::add);
    return intents;
  }

  private PendingIntent updateCommentIntent(
      String ticketId, String orgExtId, OrganizationConfig config, NegotiationContext context) {
    String body = buildBody(ticketId, config.ticketStrategy(), context);
    String payload = serialize(new AddCommentPayload(body, CommentType.HTML));
    return new PendingIntent(
        OutboxIntentType.ADD_COMMENT,
        TargetKind.TICKET,
        ticketId,
        context.negotiationId(),
        orgExtId,
        payload);
  }

  private Optional<PendingIntent> attachmentIntent(
      String ticketId, String orgExtId, NegotiationContext context) {
    List<String> attachmentIds =
        attachmentForwardingService.notYetForwardedToTicketIds(
            orgExtId, ticketId, context.candidateAttachmentIds());
    if (attachmentIds.isEmpty()) {
      return Optional.empty();
    }
    String payload =
        serialize(
            new AddNegotiationAttachmentPayload(
                ATTACHMENT_COMMENT_TEXT, context.negotiationId(), attachmentIds));
    return Optional.of(
        new PendingIntent(
            OutboxIntentType.ADD_COMMENT,
            TargetKind.TICKET,
            ticketId,
            context.negotiationId(),
            orgExtId,
            payload));
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
          context.negotiation(), ticketResources);
    }

    // PER_RESOURCE: find the single resource for this ticket.
    EntityModelResourceWithStatusDTO resource =
        context.allMappings().stream()
            .filter(mapping -> mapping.getId().getHelpdeskTicketId().equals(ticketId))
            .findFirst()
            .map(mapping -> context.resourceById().get(mapping.getId().getResourceId()))
            .orElseThrow();
    return NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithSingleResource(
        context.negotiation(), resource);
  }

  private static Map<String, String> getDistinctTicketIdToOrgExtId(List<TicketMapping> mappings) {
    Map<String, String> result = new LinkedHashMap<>();
    for (TicketMapping m : mappings) {
      result.putIfAbsent(m.getId().getHelpdeskTicketId(), m.getOrganizationExtId());
    }
    return result;
  }
}
