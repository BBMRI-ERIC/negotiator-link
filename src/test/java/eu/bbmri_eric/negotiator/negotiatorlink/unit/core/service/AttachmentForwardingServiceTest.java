package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachmentId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.ForwardedNegotiationPayloadAttachmentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService.ResolvedAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.AttachmentMetadataDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttachmentForwardingServiceTest {

  private static final String NEGOTIATION_ID = "neg-1";

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private ForwardedNegotiationPayloadAttachmentRepository forwardedAttachmentRepository;
  @Mock private OrganizationConfig config;
  @Captor private ArgumentCaptor<ForwardedNegotiationPayloadAttachment> rowCaptor;

  private AttachmentForwardingService service;

  @BeforeEach
  void setUp() {
    service = new AttachmentForwardingService(negotiatorClient, forwardedAttachmentRepository);
  }

  @Test
  void resolve_keepsOnlyNegotiationLevelAndNotYetForwarded() {
    var negotiationLevel = metadata("a-1", null, "public.txt", "text/plain");
    var orgScoped = metadata("a-2", "org-9", "private.txt", "text/plain");
    var alreadyForwarded = metadata("a-3", null, "old.txt", "text/plain");

    when(forwardedAttachmentRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(forwarded("a-3")));
    when(negotiatorClient.getAttachments(NEGOTIATION_ID, config))
        .thenReturn(List.of(negotiationLevel, orgScoped, alreadyForwarded));
    when(negotiatorClient.downloadAttachment("a-1", config)).thenReturn("bytes".getBytes());

    List<ResolvedAttachment> attachments =
        service.resolveNegotiationPayloadAttachmentsToForward(NEGOTIATION_ID, config);

    assertThat(attachments).hasSize(1);
    assertThat(attachments.getFirst().attachmentId()).isEqualTo("a-1");
    assertThat(attachments.getFirst().attachment().filename()).isEqualTo("public.txt");
    verify(negotiatorClient, never()).downloadAttachment("a-2", config);
    verify(negotiatorClient, never()).downloadAttachment("a-3", config);
  }

  @Test
  void resolve_allAlreadyForwarded_returnsEmpty() {
    var one = metadata("a-1", null, "public.txt", "text/plain");

    when(forwardedAttachmentRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(forwarded("a-1")));
    when(negotiatorClient.getAttachments(NEGOTIATION_ID, config)).thenReturn(List.of(one));

    List<ResolvedAttachment> attachments =
        service.resolveNegotiationPayloadAttachmentsToForward(NEGOTIATION_ID, config);

    assertThat(attachments).isEmpty();
    verify(negotiatorClient, never()).downloadAttachment(any(), any());
  }

  @Test
  void recordForwarded_savesOneRowPerId() {
    service.recordForwarded(NEGOTIATION_ID, List.of("a-1", "a-2"));

    verify(forwardedAttachmentRepository, times(2)).save(rowCaptor.capture());
    assertThat(rowCaptor.getAllValues())
        .extracting(row -> row.getId().getAttachmentId())
        .containsExactly("a-1", "a-2");
    assertThat(rowCaptor.getAllValues())
        .allMatch(row -> row.getId().getNegotiationId().equals(NEGOTIATION_ID));
  }

  @Test
  void recordForwarded_emptyList_savesNothing() {
    service.recordForwarded(NEGOTIATION_ID, List.of());

    verifyNoInteractions(forwardedAttachmentRepository);
  }

  private AttachmentMetadataDTO metadata(
      String id, String organizationId, String name, String contentType) {
    return new AttachmentMetadataDTO()
        .id(id)
        .organizationId(organizationId)
        .name(name)
        .contentType(contentType);
  }

  private ForwardedNegotiationPayloadAttachment forwarded(String attachmentId) {
    return new ForwardedNegotiationPayloadAttachment(
        new ForwardedNegotiationPayloadAttachmentId(NEGOTIATION_ID, attachmentId));
  }
}
