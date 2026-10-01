package eu.bbmri_eric.negotiator.negotiatorlink.core.service;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachmentId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.ForwardedNegotiationPayloadAttachmentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.AttachmentMetadataDTO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves which negotiation-level attachments to forward and records, per ticket, which have
 * already been forwarded. The forwardable set is downloaded once per event; the already-forwarded
 * subtraction is scoped per {@code (organization, ticket)} so a failed forward to one ticket does
 * not affect the others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentForwardingService {

  private static final String PDF_CONTENT_TYPE = "application/pdf";
  private static final String PDF_SUMMARY_FILENAME_PREFIX = "negotiation_summary_";
  private static final DateTimeFormatter PDF_SUMMARY_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

  private final NegotiatorClient negotiatorClient;
  private final ForwardedNegotiationPayloadAttachmentRepository forwardedAttachmentRepository;

  /**
   * A resolved attachment ready to forward: the Negotiator attachment ID (needed to record the row
   * after upload) paired with the downloaded {@link Attachment}.
   */
  public record ResolvedAttachment(String attachmentId, Attachment attachment) {}

  /**
   * All negotiation-level attachments currently forwardable for {@code negotiationId}, downloaded
   * once. Caller subtracts the per-ticket already-forwarded set via {@link
   * #notYetForwardedToTicket}. Returns an empty list when there is nothing to forward.
   */
  public List<ResolvedAttachment> resolveForwardablePayloadAttachments(
      String negotiationId, OrganizationConfig config) {
    var metadata = negotiatorClient.getAttachments(negotiationId, config);
    log.debug("Retrieved {} attachments for negotiation '{}'", metadata.size(), negotiationId);
    return metadata.stream()
        // TODO: once the Negotiator post <--> attachment link ships and AttachmentMetadataDTO
        //       exposes postId, extend to: getOrganizationId() == null && getPostId() == null
        .filter(a -> a.getOrganizationId() == null)
        .map(a -> download(a, config))
        .toList();
  }

  /**
   * Subtracts the attachments already forwarded to {@code (orgExtId, ticketId)} from {@code
   * candidates}, returning only those still to be forwarded to that ticket. DB-only; performs no
   * HTTP.
   */
  public List<ResolvedAttachment> notYetForwardedToTicket(
      String orgExtId, String ticketId, List<ResolvedAttachment> candidates) {
    Set<String> alreadyForwarded =
        forwardedAttachmentRepository
            .findByIdOrganizationExtIdAndIdTicketId(orgExtId, ticketId)
            .stream()
            .map(fa -> fa.getId().getAttachmentId())
            .collect(Collectors.toSet());
    return candidates.stream().filter(c -> !alreadyForwarded.contains(c.attachmentId())).toList();
  }

  /** Generates the negotiation's PDF summary. */
  public Attachment getNegotiationPdfSummary(String negotiationId, OrganizationConfig config) {
    byte[] content = negotiatorClient.generateNegotiationPdf(negotiationId, config);
    return new Attachment(pdfSummaryFilename(), PDF_CONTENT_TYPE, content);
  }

  /**
   * Records one row per forwarded attachment ID for a ticket. Call only after a successful upload.
   */
  public void recordForwarded(
      String negotiationId, String orgExtId, String ticketId, List<String> attachmentIds) {
    for (String attachmentId : attachmentIds) {
      forwardedAttachmentRepository.save(
          new ForwardedNegotiationPayloadAttachment(
              new ForwardedNegotiationPayloadAttachmentId(orgExtId, ticketId, attachmentId),
              negotiationId));
    }
  }

  private String pdfSummaryFilename() {
    return PDF_SUMMARY_FILENAME_PREFIX + LocalDateTime.now().format(PDF_SUMMARY_TIMESTAMP) + ".pdf";
  }

  private ResolvedAttachment download(AttachmentMetadataDTO metadata, OrganizationConfig config) {
    byte[] content = negotiatorClient.downloadAttachment(metadata.getId(), config);
    return new ResolvedAttachment(
        metadata.getId(), new Attachment(metadata.getName(), metadata.getContentType(), content));
  }
}
