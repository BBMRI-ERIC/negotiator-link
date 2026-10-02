package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedNegotiationPayloadAttachmentId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
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
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String TICKET_ID = "ticket-42";

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
  void resolveForwardablePayloadAttachments_keepsOnlyNegotiationLevel() {
    var negotiationLevel = metadata("a-1", null, "public.txt", "text/plain");
    var orgScoped = metadata("a-2", "org-9", "private.txt", "text/plain");

    when(negotiatorClient.getAttachments(NEGOTIATION_ID, config))
        .thenReturn(List.of(negotiationLevel, orgScoped));
    when(negotiatorClient.downloadAttachment("a-1", config)).thenReturn("bytes".getBytes());

    List<ResolvedAttachment> attachments =
        service.resolveForwardablePayloadAttachments(NEGOTIATION_ID, config);

    assertThat(attachments).hasSize(1);
    assertThat(attachments.getFirst().attachmentId()).isEqualTo("a-1");
    assertThat(attachments.getFirst().attachment().filename()).isEqualTo("public.txt");
    verify(negotiatorClient, never()).downloadAttachment("a-2", config);
    verifyNoInteractions(forwardedAttachmentRepository);
  }

  @Test
  void notYetForwardedToTicket_subtractsRecordedForThatTicket_returnsDelta() {
    ResolvedAttachment a1 = resolved("a-1", "one.txt");
    ResolvedAttachment a2 = resolved("a-2", "two.txt");
    when(forwardedAttachmentRepository.findByIdOrganizationExtIdAndIdTicketId(
            ORG_EXT_ID, TICKET_ID))
        .thenReturn(List.of(forwarded("a-1")));

    List<ResolvedAttachment> delta =
        service.notYetForwardedToTicket(ORG_EXT_ID, TICKET_ID, List.of(a1, a2));

    assertThat(delta).containsExactly(a2);
    verifyNoInteractions(negotiatorClient);
  }

  @Test
  void notYetForwardedToTicket_allRecorded_returnsEmpty() {
    ResolvedAttachment a1 = resolved("a-1", "one.txt");
    when(forwardedAttachmentRepository.findByIdOrganizationExtIdAndIdTicketId(
            ORG_EXT_ID, TICKET_ID))
        .thenReturn(List.of(forwarded("a-1")));

    List<ResolvedAttachment> delta =
        service.notYetForwardedToTicket(ORG_EXT_ID, TICKET_ID, List.of(a1));

    assertThat(delta).isEmpty();
    verifyNoInteractions(negotiatorClient);
  }

  @Test
  void recordForwardedAttachments_savesOneRowPerId_withTicketAndOrgInKey() {
    service.recordForwardedAttachments(
        NEGOTIATION_ID, ORG_EXT_ID, TICKET_ID, List.of("a-1", "a-2"));

    verify(forwardedAttachmentRepository, times(2)).save(rowCaptor.capture());
    assertThat(rowCaptor.getAllValues())
        .extracting(ForwardedNegotiationPayloadAttachment::getId)
        .containsExactly(
            new ForwardedNegotiationPayloadAttachmentId(ORG_EXT_ID, TICKET_ID, "a-1"),
            new ForwardedNegotiationPayloadAttachmentId(ORG_EXT_ID, TICKET_ID, "a-2"));
    assertThat(rowCaptor.getAllValues())
        .allMatch(row -> row.getNegotiationId().equals(NEGOTIATION_ID));
  }

  @Test
  void recordForwardedAttachments_emptyList_savesNothing() {
    service.recordForwardedAttachments(NEGOTIATION_ID, ORG_EXT_ID, TICKET_ID, List.of());

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

  private ResolvedAttachment resolved(String attachmentId, String filename) {
    return new ResolvedAttachment(
        attachmentId, new Attachment(filename, "text/plain", "bytes".getBytes()));
  }

  private ForwardedNegotiationPayloadAttachment forwarded(String attachmentId) {
    return new ForwardedNegotiationPayloadAttachment(
        new ForwardedNegotiationPayloadAttachmentId(ORG_EXT_ID, TICKET_ID, attachmentId),
        NEGOTIATION_ID);
  }
}
