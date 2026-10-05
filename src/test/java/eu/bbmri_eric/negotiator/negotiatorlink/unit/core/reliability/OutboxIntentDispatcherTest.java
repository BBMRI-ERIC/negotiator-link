package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentDispatcher;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreatePostPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreateTicketPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreateTicketPayload.ResourceMappingRef;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.LinkTicketsPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendBulkResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class OutboxIntentDispatcherTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-7";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String ORG_ID = "42";
  private static final String RESOURCE_SOURCE_ID = "biobank:DE:MHH:collection1";
  private static final String EVENT = "MARK_AS_CHECKING_AVAILABILITY";
  private static final String SIGNED_TEXT = "From: Jane <jane@mhh.de>\n\nHello";

  @Mock private OrganizationConfigRegistry registry;
  @Mock private HelpdeskClient helpdeskClient;
  @Mock private NegotiatorClient negotiatorClient;
  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedPostRepository originatedPostRepository;
  @Mock private AttachmentForwardingService attachmentForwardingService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private OrganizationConfig config;
  private OutboxIntentDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    dispatcher =
        new OutboxIntentDispatcher(
            registry,
            Map.of(HelpdeskType.ZAMMAD, helpdeskClient),
            negotiatorClient,
            ticketMappingRepository,
            originatedPostRepository,
            attachmentForwardingService,
            objectMapper);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
  }

  @Test
  void dispatch_sendResourceEvent_callsNegotiator() {
    dispatcher.dispatch(
        intent(
            OutboxIntentType.SEND_RESOURCE_EVENT,
            new SendResourceEventPayload(NEGOTIATION_ID, RESOURCE_SOURCE_ID, EVENT)));

    verify(negotiatorClient)
        .sendResourceEvent(eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), eq(EVENT), eq(config));
  }

  @Test
  void dispatch_sendResourceEvent_failure_propagatesToDeliverer() {
    doThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null))
        .when(negotiatorClient)
        .sendResourceEvent(any(), any(), any(), any());
    assertThrows(
        HttpClientErrorException.class,
        () ->
            dispatcher.dispatch(
                intent(
                    OutboxIntentType.SEND_RESOURCE_EVENT,
                    new SendResourceEventPayload(NEGOTIATION_ID, RESOURCE_SOURCE_ID, EVENT))));
  }

  @Test
  void dispatch_sendBulkResourceEvent_callsNegotiator() {
    dispatcher.dispatch(
        intent(
            OutboxIntentType.SEND_BULK_RESOURCE_EVENT,
            new SendBulkResourceEventPayload(NEGOTIATION_ID, List.of(101L, 102L), "CHECKING")));

    verify(negotiatorClient)
        .sendBulkResourceEvent(
            eq(NEGOTIATION_ID), eq(List.of(101L, 102L)), eq("CHECKING"), eq(config));
  }

  @Test
  void dispatch_createPost_originatedPostPresent_createsPrivatePost() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(ticketMapping()));
    when(originatedPostRepository.findFirstByTicketIdAndOrganizationId(TICKET_ID, ORG_ID))
        .thenReturn(
            Optional.of(new OriginatedPost(HashUtil.sha256Hex(SIGNED_TEXT), ORG_ID, TICKET_ID)));

    dispatcher.dispatch(
        intent(
            OutboxIntentType.CREATE_POST,
            new CreatePostPayload(NEGOTIATION_ID, TICKET_ID, SIGNED_TEXT)));

    verify(negotiatorClient)
        .createPost(
            eq(NEGOTIATION_ID),
            argThat(
                (PostCreateDTO p) ->
                    SIGNED_TEXT.equals(p.getText())
                        && PostCreateDTO.TypeEnum.PRIVATE.equals(p.getType())
                        && ORG_EXT_ID.equals(p.getOrganizationId())),
            eq(config));
  }

  @Test
  void dispatch_createPost_originatedPostConsumedByEcho_skipsCreate() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(ticketMapping()));
    when(originatedPostRepository.findFirstByTicketIdAndOrganizationId(TICKET_ID, ORG_ID))
        .thenReturn(Optional.empty());

    dispatcher.dispatch(
        intent(
            OutboxIntentType.CREATE_POST,
            new CreatePostPayload(NEGOTIATION_ID, TICKET_ID, SIGNED_TEXT)));

    verify(negotiatorClient, never()).createPost(any(), any(), any());
  }

  @Test
  void dispatch_createTicket_callsHelpdeskAndPersistsMappings() {
    var ref = new ResourceMappingRef("r-1", RESOURCE_SOURCE_ID, ORG_ID);
    var payload =
        new CreateTicketPayload(
            NEGOTIATION_ID,
            "title",
            "APPROVED",
            "SUBMITTED",
            "body",
            List.of(),
            List.of(ref),
            List.of());
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(TICKET_ID);
    when(attachmentForwardingService.resolveForwardablePayloadAttachments(
            eq(NEGOTIATION_ID), eq(config)))
        .thenReturn(List.of());

    dispatcher.dispatch(intent(OutboxIntentType.CREATE_TICKET, payload));

    verify(helpdeskClient).createTicket(any(), eq(config));
    verify(ticketMappingRepository).save(any());
  }

  @Test
  void dispatch_linkTickets_callsHelpdeskLinkWhenTwoTicketsResolved() {
    var payload = new LinkTicketsPayload(NEGOTIATION_ID, List.of("r-1", "r-2"));
    var mapping1 =
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, "ticket-1", "r-1"), "r-1", ORG_ID, ORG_EXT_ID);
    var mapping2 =
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, "ticket-2", "r-2"), "r-2", ORG_ID, ORG_EXT_ID);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(NEGOTIATION_ID, "r-1"))
        .thenReturn(Optional.of(mapping1));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(NEGOTIATION_ID, "r-2"))
        .thenReturn(Optional.of(mapping2));

    dispatcher.dispatch(intent(OutboxIntentType.LINK_TICKETS, payload));

    verify(helpdeskClient).linkRelatedTickets(List.of("ticket-1", "ticket-2"), config);
  }

  @Test
  void dispatch_linkTickets_singleTicketResolved_doesNotCallLink() {
    var payload = new LinkTicketsPayload(NEGOTIATION_ID, List.of("r-1", "r-2"));
    var mapping =
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "r-1"), "r-1", ORG_ID, ORG_EXT_ID);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(NEGOTIATION_ID, "r-1"))
        .thenReturn(Optional.of(mapping));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(NEGOTIATION_ID, "r-2"))
        .thenReturn(Optional.empty());

    dispatcher.dispatch(intent(OutboxIntentType.LINK_TICKETS, payload));

    verify(helpdeskClient, never()).linkRelatedTickets(any(), any());
  }

  @Test
  void dispatch_addComment_callsHelpdeskWithTextAndType() {
    var payload = new AddCommentPayload("Hello ticket", CommentType.PLAIN_TEXT);

    dispatcher.dispatch(ticketIntent(OutboxIntentType.ADD_COMMENT, payload));

    verify(helpdeskClient)
        .addComment(eq(TICKET_ID), eq("Hello ticket"), eq(CommentType.PLAIN_TEXT), eq(config));
  }

  @Test
  void dispatch_addSystemComment_callsHelpdeskWithMessage() {
    var payload = new AddSystemCommentPayload("Status changed to Paused");

    dispatcher.dispatch(ticketIntent(OutboxIntentType.ADD_SYSTEM_COMMENT, payload));

    verify(helpdeskClient)
        .addSystemComment(eq(TICKET_ID), eq("Status changed to Paused"), eq(config));
  }

  @Test
  void dispatch_setNegotiationState_callsHelpdeskUpdateNegotiationState() {
    var payload = new SetStatePayload("negotiationState", "PAUSED");

    dispatcher.dispatch(ticketIntent(OutboxIntentType.SET_NEGOTIATION_STATE, payload));

    verify(helpdeskClient).updateNegotiationState(eq(TICKET_ID), eq("PAUSED"), eq(config));
  }

  @Test
  void dispatch_setResourceState_callsHelpdeskUpdateResourceState() {
    var payload = new SetStatePayload("resourceState", "CHECKING_AVAILABILITY");

    dispatcher.dispatch(ticketIntent(OutboxIntentType.SET_RESOURCE_STATE, payload));

    verify(helpdeskClient)
        .updateResourceState(eq(TICKET_ID), eq("CHECKING_AVAILABILITY"), eq(config));
  }

  @Test
  void dispatch_setTicketState_callsHelpdeskUpdateTicketState() {
    var payload = new SetStatePayload("ticketState", "closed");

    dispatcher.dispatch(ticketIntent(OutboxIntentType.SET_TICKET_STATE, payload));

    verify(helpdeskClient).updateTicketState(eq(TICKET_ID), eq("closed"), eq(config));
  }

  private OutboxIntent ticketIntent(OutboxIntentType type, Object payload) {
    return new OutboxIntent(
        1L,
        type,
        TargetKind.TICKET.name(),
        TICKET_ID,
        NEGOTIATION_ID,
        ORG_EXT_ID,
        serialize(payload));
  }

  private TicketMapping ticketMapping() {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "101"),
        RESOURCE_SOURCE_ID,
        ORG_ID,
        ORG_EXT_ID);
  }

  private OutboxIntent intent(OutboxIntentType type, Object payload) {
    return new OutboxIntent(
        1L,
        type,
        TargetKind.NEGOTIATION.name(),
        NEGOTIATION_ID,
        NEGOTIATION_ID,
        ORG_EXT_ID,
        serialize(payload));
  }

  private String serialize(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
