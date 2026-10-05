package eu.bbmri_eric.negotiator.negotiatorlink.core.service;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachmentId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.ForwardedNegotiationPayloadAttachmentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.AttachmentMetadataDTO;
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

  private final NegotiatorClient negotiatorClient;
  private final ForwardedNegotiationPayloadAttachmentRepository forwardedAttachmentRepository;

  /**
   * A resolved attachment ready to forward: the Negotiator attachment ID (needed to record the row
   * after upload) paired with the downloaded {@link Attachment}.
   */
  public record ResolvedAttachment(String attachmentId, Attachment attachment) {}

  /**
   * Return all negotiation-level attachments for {@code negotiationId} minus those whose IDs are in
   * {@code forwardedAttachmentIds}.
   */
  public List<ResolvedAttachment> getNegotiationPayloadAttachmentsToForward(
      String negotiationId, OrganizationConfig config, Set<String> forwardedAttachmentIds) {
    List<AttachmentMetadataDTO> metadata = negotiatorClient.getAttachments(negotiationId, config);
    log.debug("Retrieved {} attachments for negotiation '{}'", metadata.size(), negotiationId);
    return metadata.stream()
        .filter(a -> !forwardedAttachmentIds.contains(a.getId()))
        // TODO: once the Negotiator post <--> attachment link ships and AttachmentMetadataDTO
        //       exposes postId, extend to: getOrganizationId() == null && getPostId() == null
        .filter(a -> a.getOrganizationId() == null)
        .map(a -> download(a, config))
        .toList();
  }

  /**
   * Return all negotiation-level attachment IDs for {@code negotiationId} minus those whose IDs are
   * in {@code forwardedAttachmentIds}.
   */
  public List<String> getNegotiationPayloadAttachmentsIds(
      String negotiationId, OrganizationConfig config) {
    List<AttachmentMetadataDTO> metadata = negotiatorClient.getAttachments(negotiationId, config);
    return metadata.stream()
        // TODO: once the Negotiator post <--> attachment link ships and AttachmentMetadataDTO
        //       exposes postId, extend to: getOrganizationId() == null && getPostId() == null
        .filter(a -> a.getOrganizationId() == null)
        .map(AttachmentMetadataDTO::getId)
        .toList();
  }

  /**
   * Subtracts the attachments already forwarded to {@code (orgExtId, ticketId)} from {@code
   * candidateIds}, returning only those that should be forwarded to the ticket.
   */
  public List<String> notYetForwardedToTicketIds(
      String orgExtId, String ticketId, List<String> candidateIds) {
    Set<String> alreadyForwarded = alreadyForwardedIds(orgExtId, ticketId);
    return candidateIds.stream().filter(id -> !alreadyForwarded.contains(id)).toList();
  }

  /** The IDs of attachments already forwarded to {@code (orgExtId, ticketId)}. */
  public Set<String> alreadyForwardedIds(String orgExtId, String ticketId) {
    return forwardedAttachmentRepository
        .findByIdOrganizationExtIdAndIdTicketId(orgExtId, ticketId)
        .stream()
        .map(fa -> fa.getId().getAttachmentId())
        .collect(Collectors.toSet());
  }

  /**
   * Records one row per forwarded attachment ID for a ticket. Call only after a successful upload.
   */
  public void recordForwardedAttachments(
      String negotiationId, String orgExtId, String ticketId, List<String> attachmentIds) {
    for (String attachmentId : attachmentIds) {
      forwardedAttachmentRepository.save(
          new ForwardedNegotiationPayloadAttachment(
              new ForwardedNegotiationPayloadAttachmentId(orgExtId, ticketId, attachmentId),
              negotiationId));
    }
  }

  private ResolvedAttachment download(AttachmentMetadataDTO metadata, OrganizationConfig config) {
    byte[] content = negotiatorClient.downloadAttachment(metadata.getId(), config);
    return new ResolvedAttachment(
        metadata.getId(), new Attachment(metadata.getName(), metadata.getContentType(), content));
  }
}
