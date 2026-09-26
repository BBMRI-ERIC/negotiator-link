package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachmentId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForwardedNegotiationPayloadAttachmentRepository
    extends JpaRepository<
        ForwardedNegotiationPayloadAttachment, ForwardedNegotiationPayloadAttachmentId> {

  List<ForwardedNegotiationPayloadAttachment> findByIdNegotiationId(String negotiationId);
}
