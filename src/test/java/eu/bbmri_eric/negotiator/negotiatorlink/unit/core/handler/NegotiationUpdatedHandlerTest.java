package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService.ResolvedAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NegotiationUpdatedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String DISPLAY_ID = "NEGO-1";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_INTERNAL_ID = 10L;
  private static final String TICKET_ID = "ticket-42";
  private static final String RESOURCE_ID = "1";
  private static final String RESOURCE_SOURCE_ID = "src-1";

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private OrganizationConfigRegistry registry;
  @Mock private HelpdeskClient helpdeskClient;
  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private AttachmentForwardingService attachmentForwardingService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationUpdatedHandler(
            negotiatorClient,
            registry,
            Map.of(HelpdeskType.ZAMMAD, helpdeskClient),
            ticketMappingRepository,
            attachmentForwardingService,
            objectMapper);
  }

  @Test
  void handle_noMappedTickets_doesNotAddComment() throws Exception {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    handler.handle(updatedEventNode(NEGOTIATION_ID));

    verify(helpdeskClient, never()).addComment(anyString(), anyString(), any(), any());
  }

  @Test
  void handle_nullNegotiationId_doesNotAddComment() throws Exception {
    handler.handle(updatedEventNode(null));

    verify(helpdeskClient, never()).addComment(anyString(), anyString(), any(), any());
    verify(ticketMappingRepository, never()).findByIdNegotiationId(anyString());
  }

  @Test
  void handle_singlePerResourceTicket_addsCommentWithUpdateNote() throws Exception {
    var config = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    var negotiation = TestUtil.negotiation(NEGOTIATION_ID, DISPLAY_ID, "IN_PROGRESS");
    var resource =
        TestUtil.resource(1L, RESOURCE_SOURCE_ID, "My Collection", ORG_EXT_ID, ORG_INTERNAL_ID);
    var mapping = mapping(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID, RESOURCE_SOURCE_ID);

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(negotiation);
    when(negotiatorClient.getResources(NEGOTIATION_ID, config)).thenReturn(List.of(resource));

    handler.handle(updatedEventNode(NEGOTIATION_ID));

    verify(helpdeskClient, times(1))
        .addComment(eq(TICKET_ID), contains("updated"), eq(CommentType.HTML), eq(config));
  }

  @Test
  void handle_twoPerResourceTicketsDifferentOrgs_addsCommentToEach() throws Exception {
    var orgExtId2 = "bbmri:NL:AMC";
    var ticketId2 = "ticket-99";
    var config1 = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    var config2 = TestUtil.organizationConfig(orgExtId2, TicketStrategy.PER_RESOURCE);
    var negotiation = TestUtil.negotiation(NEGOTIATION_ID, DISPLAY_ID, "IN_PROGRESS");
    var resource1 =
        TestUtil.resource(1L, RESOURCE_SOURCE_ID, "Collection 1", ORG_EXT_ID, ORG_INTERNAL_ID);
    var resource2 = TestUtil.resource(2L, "src-2", "Collection 2", orgExtId2, 20L);
    var mapping1 = mapping(NEGOTIATION_ID, TICKET_ID, "1", RESOURCE_SOURCE_ID);
    var mapping2 = mapping(NEGOTIATION_ID, ticketId2, "2", "src-2", orgExtId2);

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config1));
    when(registry.findByExternalId(orgExtId2)).thenReturn(Optional.of(config2));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config1)).thenReturn(negotiation);
    when(negotiatorClient.getResources(NEGOTIATION_ID, config1))
        .thenReturn(List.of(resource1, resource2));

    handler.handle(updatedEventNode(NEGOTIATION_ID));

    verify(helpdeskClient, times(1))
        .addComment(eq(TICKET_ID), contains("updated"), eq(CommentType.HTML), eq(config1));
    verify(helpdeskClient, times(1))
        .addComment(eq(ticketId2), contains("updated"), eq(CommentType.HTML), eq(config2));
    verify(helpdeskClient, never()).addComment(anyString(), anyString(), any(), anyList(), any());
    verify(attachmentForwardingService, never()).recordForwarded(any(), any());
  }

  @Test
  void handle_newAttachments_forwardsThemInSeparateCommentAndRecordsAll() throws Exception {
    var config = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    var negotiation = TestUtil.negotiation(NEGOTIATION_ID, DISPLAY_ID, "IN_PROGRESS");
    var resource =
        TestUtil.resource(1L, RESOURCE_SOURCE_ID, "My Collection", ORG_EXT_ID, ORG_INTERNAL_ID);
    var mapping = mapping(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID, RESOURCE_SOURCE_ID);
    var attachment =
        new ResolvedAttachment(
            "a-1", new Attachment("public.txt", "text/plain", "bytes".getBytes()));

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(negotiation);
    when(negotiatorClient.getResources(NEGOTIATION_ID, config)).thenReturn(List.of(resource));
    when(attachmentForwardingService.resolveNegotiationPayloadAttachmentsToForward(
            NEGOTIATION_ID, config))
        .thenReturn(List.of(attachment));

    handler.handle(updatedEventNode(NEGOTIATION_ID));

    verify(helpdeskClient, times(1))
        .addComment(
            eq(TICKET_ID),
            contains("negotiation has been updated"),
            eq(CommentType.HTML),
            eq(config));
    verify(helpdeskClient, times(1))
        .addComment(
            eq(TICKET_ID),
            contains("negotiation-level attachments"),
            eq(CommentType.PLAIN_TEXT),
            anyList(),
            eq(config));
    verify(attachmentForwardingService).recordForwarded(NEGOTIATION_ID, List.of("a-1"));
  }

  @Test
  void handle_perNegotiationTicket_addsOneCommentWithAllResources() throws Exception {
    var config = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);
    var negotiation = TestUtil.negotiation(NEGOTIATION_ID, DISPLAY_ID, "IN_PROGRESS");
    var resource1 =
        TestUtil.resource(1L, RESOURCE_SOURCE_ID, "Collection 1", ORG_EXT_ID, ORG_INTERNAL_ID);
    var resource2 = TestUtil.resource(2L, "src-2", "Collection 2", ORG_EXT_ID, ORG_INTERNAL_ID);
    var mapping1 = mapping(NEGOTIATION_ID, TICKET_ID, "1", RESOURCE_SOURCE_ID);
    var mapping2 = mapping(NEGOTIATION_ID, TICKET_ID, "2", "src-2");

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(negotiation);
    when(negotiatorClient.getResources(NEGOTIATION_ID, config))
        .thenReturn(List.of(resource1, resource2));

    handler.handle(updatedEventNode(NEGOTIATION_ID));

    verify(helpdeskClient, times(1))
        .addComment(eq(TICKET_ID), contains("updated"), eq(CommentType.HTML), eq(config));
  }

  @Test
  void handle_addCommentThrows_exceptionSwallowedAndOtherTicketsStillProcessed() throws Exception {
    var orgExtId2 = "bbmri:NL:AMC";
    var ticketId2 = "ticket-99";
    var config1 = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    var config2 = TestUtil.organizationConfig(orgExtId2, TicketStrategy.PER_RESOURCE);
    var negotiation = TestUtil.negotiation(NEGOTIATION_ID, DISPLAY_ID, "IN_PROGRESS");
    var resource1 =
        TestUtil.resource(1L, RESOURCE_SOURCE_ID, "Collection 1", ORG_EXT_ID, ORG_INTERNAL_ID);
    var resource2 = TestUtil.resource(2L, "src-2", "Collection 2", orgExtId2, 20L);
    var mapping1 = mapping(NEGOTIATION_ID, TICKET_ID, "1", RESOURCE_SOURCE_ID);
    var mapping2 = mapping(NEGOTIATION_ID, ticketId2, "2", "src-2", orgExtId2);

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config1));
    when(registry.findByExternalId(orgExtId2)).thenReturn(Optional.of(config2));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config1)).thenReturn(negotiation);
    when(negotiatorClient.getResources(NEGOTIATION_ID, config1))
        .thenReturn(List.of(resource1, resource2));
    doThrow(new RuntimeException("helpdesk down"))
        .when(helpdeskClient)
        .addComment(eq(TICKET_ID), anyString(), any(), eq(config1));

    handler.handle(updatedEventNode(NEGOTIATION_ID));

    // First ticket failed but second must still be attempted.
    verify(helpdeskClient, times(1))
        .addComment(eq(ticketId2), contains("updated"), eq(CommentType.HTML), eq(config2));
  }

  private JsonNode updatedEventNode(String negotiationId) throws Exception {
    String json = negotiationId == null ? "{}" : "{\"negotiationId\":\"" + negotiationId + "\"}";
    return objectMapper.readTree(json);
  }

  private TicketMapping mapping(
      String negotiationId, String ticketId, String resourceId, String resourceSourceId) {
    return mapping(negotiationId, ticketId, resourceId, resourceSourceId, ORG_EXT_ID);
  }

  private TicketMapping mapping(
      String negotiationId,
      String ticketId,
      String resourceId,
      String resourceSourceId,
      String orgExtId) {
    var id = new TicketMappingId(negotiationId, ticketId, resourceId);
    return new TicketMapping(id, resourceSourceId, String.valueOf(ORG_INTERNAL_ID), orgExtId);
  }
}
