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
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService.ResolvedAttachment;
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
  private final AttachmentForwardingService attachmentForwardingService;
  private final ObjectMapper objectMapper;

  /** Negotiation-wide data shared by every updated ticket, resolved once per event. */
  private record NegotiationContext(
      String negotiationId,
      EntityModelNegotiationDTO negotiation,
      Map<String, EntityModelResourceWithStatusDTO> resourceById,
      List<TicketMapping> allMappings,
      List<ResolvedAttachment> candidateAttachments,
      Attachment pdfSummary) {}

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

    List<TicketMapping> mappings = ticketMappingRepository.findByIdNegotiationId(negotiationId);
    if (mappings.isEmpty()) {
      log.debug("No mapped tickets for negotiation '{}' — skipping update comment", negotiationId);
      return;
    }

    OrganizationConfig fetchConfig =
        registry.findByExternalId(mappings.getFirst().getOrganizationExtId()).orElseThrow();

    NegotiationContext context = buildContext(negotiationId, mappings, fetchConfig);

    getDistinctTicketIdToOrgExtId(mappings)
        .forEach((ticketId, orgExtId) -> processTicket(ticketId, orgExtId, context));
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
    List<ResolvedAttachment> candidateAttachments =
        attachmentForwardingService.resolveForwardablePayloadAttachments(negotiationId, config);
    Attachment pdfSummary =
        attachmentForwardingService.getNegotiationPdfSummary(negotiationId, config);
    return new NegotiationContext(
        negotiationId, negotiation, resourceById, mappings, candidateAttachments, pdfSummary);
  }

  private void processTicket(String ticketId, String orgExtId, NegotiationContext context) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElse(null);
    if (config == null) {
      log.warn("Org '{}' not in config for ticket '{}' — skipping", orgExtId, ticketId);
      return;
    }
    HelpdeskClient client = clients.get(config.type());
    postUpdateComment(ticketId, config, client, context);
    forwardNewAttachments(ticketId, orgExtId, config, client, context);
  }

  private void postUpdateComment(
      String ticketId,
      OrganizationConfig config,
      HelpdeskClient client,
      NegotiationContext context) {
    String body = buildBody(ticketId, config.ticketStrategy(), context);
    try {
      client.addComment(ticketId, body, CommentType.HTML, List.of(context.pdfSummary()), config);
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

  private void forwardNewAttachments(
      String ticketId,
      String orgExtId,
      OrganizationConfig config,
      HelpdeskClient client,
      NegotiationContext context) {
    String negotiationId = context.negotiationId();
    List<ResolvedAttachment> toForward =
        attachmentForwardingService.notYetForwardedToTicket(
            orgExtId, ticketId, context.candidateAttachments());
    if (toForward.isEmpty()) {
      return;
    }
    try {
      client.addComment(
          ticketId,
          "New negotiation-level attachments added by requestor. See the files below.",
          CommentType.PLAIN_TEXT,
          toForward.stream().map(ResolvedAttachment::attachment).toList(),
          config);
      attachmentForwardingService.recordForwarded(
          negotiationId,
          orgExtId,
          ticketId,
          toForward.stream().map(ResolvedAttachment::attachmentId).toList());
      log.info(
          "Forwarded {} file(s) to ticket '{}' (negotiation '{}')",
          toForward.size(),
          ticketId,
          negotiationId);
    } catch (Exception e) {
      log.error(
          "Failed to forward attachments to ticket '{}' for negotiation '{}'",
          ticketId,
          negotiationId,
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
