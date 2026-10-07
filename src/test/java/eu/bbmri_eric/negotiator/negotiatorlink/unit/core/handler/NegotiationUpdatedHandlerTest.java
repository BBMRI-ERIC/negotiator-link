package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HandlerResult;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationCache;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddNegotiationAttachmentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.util.List;
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
  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private AttachmentForwardingService attachmentForwardingService;
  @Mock private PendingTicketCreationCache pendingTicketCreationCache;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationUpdatedHandler(
            negotiatorClient,
            registry,
            ticketMappingRepository,
            attachmentForwardingService,
            pendingTicketCreationCache,
            objectMapper);
  }

  @Test
  void handle_noMappedTickets_cacheAbsent_returnsNoIntents() throws Exception {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());
    when(pendingTicketCreationCache.isTracked(NEGOTIATION_ID)).thenReturn(false);

    assertThat(handler.handle(updatedEventNode(NEGOTIATION_ID)).intents()).isEmpty();
  }

  @Test
  void handle_noMappedTickets_cachePresent_throwsPendingTicketCreationException() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());
    when(pendingTicketCreationCache.isTracked(NEGOTIATION_ID)).thenReturn(true);

    assertThatThrownBy(() -> handler.handle(updatedEventNode(NEGOTIATION_ID)))
        .isInstanceOf(PendingTicketCreationException.class);
  }

  @Test
  void handle_nullNegotiationId_returnsNoIntents() throws Exception {
    assertThat(handler.handle(updatedEventNode(null)).intents()).isEmpty();
    verify(ticketMappingRepository, never()).findByIdNegotiationId(anyString());
  }

  @Test
  void handle_singlePerResourceTicket_emitsHtmlCommentIntentWithUpdateNote() throws Exception {
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

    HandlerResult result = handler.handle(updatedEventNode(NEGOTIATION_ID));
    assertThat(result.originatedPost()).isEmpty();

    List<PendingIntent> intents = result.intents();

    assertThat(intents).hasSize(1);
    PendingIntent intent = intents.getFirst();
    assertThat(intent.outboxIntentType()).isEqualTo(OutboxIntentType.ADD_COMMENT);
    assertThat(intent.targetId()).isEqualTo(TICKET_ID);
    assertThat(intent.organizationExtId()).isEqualTo(ORG_EXT_ID);
    AddCommentPayload payload = readComment(intent);
    assertThat(payload.text()).contains("updated");
    assertThat(payload.commentType()).isEqualTo(CommentType.HTML);
  }

  @Test
  void handle_twoPerResourceTicketsDifferentOrgs_emitsCommentIntentForEach() throws Exception {
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

    List<PendingIntent> intents = handler.handle(updatedEventNode(NEGOTIATION_ID)).intents();

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .allSatisfy(i -> assertThat(i.outboxIntentType()).isEqualTo(OutboxIntentType.ADD_COMMENT));
    assertThat(intents)
        .extracting(PendingIntent::targetId)
        .containsExactlyInAnyOrder(TICKET_ID, ticketId2);
    assertThat(intents)
        .extracting(PendingIntent::organizationExtId)
        .containsExactlyInAnyOrder(ORG_EXT_ID, orgExtId2);
  }

  @Test
  void handle_newAttachments_emitsHtmlCommentAndAttachmentCommentIntents() throws Exception {
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
    when(attachmentForwardingService.getNegotiationPayloadAttachmentsIds(NEGOTIATION_ID, config))
        .thenReturn(List.of("a-1"));
    when(attachmentForwardingService.notYetForwardedToTicketIds(
            ORG_EXT_ID, TICKET_ID, List.of("a-1")))
        .thenReturn(List.of("a-1"));

    List<PendingIntent> intents = handler.handle(updatedEventNode(NEGOTIATION_ID)).intents();

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .allSatisfy(i -> assertThat(i.outboxIntentType()).isEqualTo(OutboxIntentType.ADD_COMMENT));
    AddNegotiationAttachmentPayload attachmentPayload =
        objectMapper.readValue(intents.get(1).payloadJson(), AddNegotiationAttachmentPayload.class);
    assertThat(attachmentPayload.negotiationAttachmentIds()).containsExactly("a-1");
    assertThat(attachmentPayload.negotiationId()).isEqualTo(NEGOTIATION_ID);
    assertThat(attachmentPayload.text()).contains("negotiation-level attachments");
  }

  @Test
  void handle_perNegotiationTicket_emitsOneCommentIntentWithAllResources() throws Exception {
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

    List<PendingIntent> intents = handler.handle(updatedEventNode(NEGOTIATION_ID)).intents();

    assertThat(intents).hasSize(1);
    assertThat(intents.getFirst().targetId()).isEqualTo(TICKET_ID);
    assertThat(readComment(intents.getFirst()).text()).contains("updated");
  }

  private AddCommentPayload readComment(PendingIntent intent) {
    try {
      return objectMapper.readValue(intent.payloadJson(), AddCommentPayload.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
