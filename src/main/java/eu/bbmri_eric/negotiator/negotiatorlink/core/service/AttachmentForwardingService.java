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
 * Resolves which negotiation-level attachments still need to be forwarded for a negotiation, and
 * records them once an upload succeeds.
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
   * The negotiation-level attachments not yet forwarded for {@code negotiationId} Returns an empty
   * list when there is nothing new.
   */
  public List<ResolvedAttachment> resolveNegotiationPayloadAttachmentsToForward(
      String negotiationId, OrganizationConfig config) {
    Set<String> alreadyForwarded =
        forwardedAttachmentRepository.findByIdNegotiationId(negotiationId).stream()
            .map(fa -> fa.getId().getAttachmentId())
            .collect(Collectors.toSet());
    var metadata = negotiatorClient.getAttachments(negotiationId, config);
    log.debug("Retrieved {} attachments for negotiation '{}'", metadata.size(), negotiationId);
    for (AttachmentMetadataDTO a : metadata) {
      log.debug(
          "Attachment {}: name='{}', contentType='{}', organizationId='{}'",
          a.getId(),
          a.getName(),
          a.getContentType(),
          a.getOrganizationId());
    }
    return metadata.stream()
        // TODO: once the Negotiator post <--> attachment link ships and AttachmentMetadataDTO
        //       exposes postId, extend to: getOrganizationId() == null && getPostId() == null
        .filter(a -> a.getOrganizationId() == null)
        .filter(a -> !alreadyForwarded.contains(a.getId()))
        .map(a -> download(a, config))
        .toList();
  }

  /** Records one row per forwarded attachment ID. Call only after a successful upload. */
  public void recordForwarded(String negotiationId, List<String> attachmentIds) {
    for (String attachmentId : attachmentIds) {
      forwardedAttachmentRepository.save(
          new ForwardedNegotiationPayloadAttachment(
              new ForwardedNegotiationPayloadAttachmentId(negotiationId, attachmentId)));
    }
  }

  private ResolvedAttachment download(AttachmentMetadataDTO metadata, OrganizationConfig config) {
    byte[] content = negotiatorClient.downloadAttachment(metadata.getId(), config);
    return new ResolvedAttachment(
        metadata.getId(), new Attachment(metadata.getName(), metadata.getContentType(), content));
  }
}
