package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService.ResolvedAttachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorNegotiationEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NegotiationStateUpdatedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String DISPLAY_ID = "NEGO-1";
  private static final String NEGOTIATION_STATE = NegotiatorNegotiationEvent.APPROVE.toState();
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_INTERNAL_ID = 10L;
  private static final String TICKET_ID = "ticket-42";
  private static final String RESOURCE_STATE = "SUBMITTED";
  private static final String PAUSED_SYSTEM_COMMENT = "Negotiation status changed to 'Paused'";
  private static final String CONCLUDED_SYSTEM_COMMENT =
      "Negotiation status changed to 'Concluded'";
  private static final Attachment PDF_SUMMARY =
      new Attachment(
          "negotiation_summary_2026-09-26_143207.pdf", "application/pdf", "%PDF".getBytes());

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private OrganizationConfigRegistry registry;
  @Mock private HelpdeskClient helpdeskClient;
  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private AttachmentForwardingService attachmentForwardingService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationStateUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationStateUpdatedHandler(
            negotiatorClient,
            registry,
            Map.of(HelpdeskType.ZAMMAD, helpdeskClient),
            ticketMappingRepository,
            attachmentForwardingService,
            objectMapper);
  }

  @Test
  void handle_perResource_multipleResources_createsSeparateTicketsAndLinks() {
    var resource1 = createResource("r-1", 1L);
    var resource2 = createResource("r-2", 2L);
    var negotiation = createNegotiation();
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var ticket1 = new CreatedTicket("ticket-1", "1");
    var ticket2 = new CreatedTicket("ticket-2", "2");

    stubHappyPath(negotiation, config, List.of(resource1, resource2));
    when(helpdeskClient.createTicket(any(CreateTicketRequest.class), eq(config)))
        .thenReturn(ticket1)
        .thenReturn(ticket2);

    handler.handle(approveEventData());

    verify(helpdeskClient).createTicket(eq(perResourceRequest(negotiation, resource1)), eq(config));
    verify(helpdeskClient).createTicket(eq(perResourceRequest(negotiation, resource2)), eq(config));
    verify(helpdeskClient).linkRelatedTickets(eq(List.of(ticket1, ticket2)), eq(config));
    verify(ticketMappingRepository).save(eq(mappingFor(ticket1, "1", resource1)));
    verify(ticketMappingRepository).save(eq(mappingFor(ticket2, "2", resource2)));
  }

  @Test
  void handle_perNegotiation_multipleResources_createsSingleTicketForAllResources() {
    var resource1 = createResource("r-1", 1L);
    var resource2 = createResource("r-2", 2L);
    var negotiation = createNegotiation();
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");

    stubHappyPath(negotiation, config, List.of(resource1, resource2));
    when(helpdeskClient.createTicket(any(CreateTicketRequest.class), eq(config)))
        .thenReturn(ticket);

    handler.handle(approveEventData());

    var expectedRequest = perNegotiationRequest(negotiation, List.of(resource1, resource2));

    verify(helpdeskClient, times(1)).createTicket(eq(expectedRequest), eq(config));
    verify(helpdeskClient, never()).linkRelatedTickets(any(), any());
    verify(ticketMappingRepository).save(eq(mappingFor(ticket, "1", resource1)));
    verify(ticketMappingRepository).save(eq(mappingFor(ticket, "2", resource2)));
  }

  @Test
  void handle_perResource_singleResource_doesNotLink() {
    var resource = createResource("r-1", 1L);
    var negotiation = createNegotiation();
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var ticket = new CreatedTicket(TICKET_ID, "42");

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(CreateTicketRequest.class), eq(config)))
        .thenReturn(ticket);

    handler.handle(approveEventData());

    verify(helpdeskClient, times(1))
        .createTicket(eq(perResourceRequest(negotiation, resource)), eq(config));
    verify(helpdeskClient, never()).linkRelatedTickets(any(), any());
  }

  @Test
  void handle_orgNotInConfig_skipsOrg() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    stubResources(createNegotiation(), config, List.of(createResource("r-1", 1L)));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    handler.handle(approveEventData());

    verify(helpdeskClient, never()).createTicket(any(), any());
    verify(ticketMappingRepository, never()).save(any());
  }

  @Test
  void handle_nonApproveEvent_doesNotCreateTickets() {
    handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    verify(negotiatorClient, never()).getResources(any(), any());
    verify(helpdeskClient, never()).createTicket(any(), any());
  }

  @Test
  void handleApprove_noOrgsConfigured_doesNothing() {
    when(registry.findAll()).thenReturn(List.of());

    handler.handle(approveEventData());

    verify(negotiatorClient, never()).getNegotiation(any(), any());
    verify(helpdeskClient, never()).createTicket(any(), any());
  }

  @Test
  void handleApprove_noResources_doesNothing() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(registry.findAll()).thenReturn(List.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(createNegotiation());
    when(negotiatorClient.getResources(NEGOTIATION_ID, config)).thenReturn(List.of());

    handler.handle(approveEventData());

    verify(helpdeskClient, never()).createTicket(any(), any());
  }

  @Test
  void handleApprove_adapterThrows_doesNotPropagateException() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var resource = createResource("r-1", 1L);
    stubHappyPath(createNegotiation(), config, List.of(resource));
    when(helpdeskClient.createTicket(any(), any())).thenThrow(new RuntimeException("adapter down"));

    assertDoesNotThrow(() -> handler.handle(approveEventData()));
  }

  @Test
  void handleApprove_withPublicAttachments_addFilesOnCreate() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var resource = createResource("r-1", 1L);
    var negotiation = createNegotiation();
    var ticket = new CreatedTicket(TICKET_ID, "42");
    var resolved =
        new ResolvedAttachment(
            "a-1", new Attachment("public.txt", "text/plain", "bytes".getBytes()));

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(CreateTicketRequest.class), eq(config)))
        .thenReturn(ticket);
    when(attachmentForwardingService.resolveForwardablePayloadAttachments(
            eq(NEGOTIATION_ID), eq(config)))
        .thenReturn(List.of(resolved));

    handler.handle(approveEventData());

    // Ticket carries both the payload attachment and the always-appended PDF summary.
    verify(helpdeskClient)
        .createTicket(argThat(request -> request.attachments().size() == 2), eq(config));
    verify(attachmentForwardingService)
        .recordForwardedAttachments(NEGOTIATION_ID, ORG_EXT_ID, TICKET_ID, List.of("a-1"));
    verify(attachmentForwardingService, never()).notYetForwardedToTicket(any(), any(), any());
  }

  @Test
  void postNegotiationCommentsToTicket_perNegotiation_postsSignedComment() {
    var negotiation = createNegotiation();
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");
    var post =
        TestUtil.post(
            "p-1",
            "Hello world",
            EntityModelPostDTO.TypeEnum.PUBLIC,
            null,
            "Alice",
            "alice@example.com");

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket);
    stubPosts(config, List.of(post));

    handler.handle(approveEventData());

    verify(helpdeskClient)
        .addComment(
            eq(TICKET_ID),
            startsWith("From: Alice <alice@example.com>"),
            eq(CommentType.PLAIN_TEXT),
            eq(config));
  }

  @Test
  void postNegotiationCommentsToTicket_perNegotiation_postsInChronologicalOrder() {
    var negotiation = createNegotiation();
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");

    var older = TestUtil.post("p-1", "first", EntityModelPostDTO.TypeEnum.PUBLIC, null);
    older.setCreationDate(LocalDateTime.of(2024, 1, 1, 10, 0));
    var newer = TestUtil.post("p-2", "second", EntityModelPostDTO.TypeEnum.PUBLIC, null);
    newer.setCreationDate(LocalDateTime.of(2024, 1, 2, 10, 0));

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket);
    stubPosts(config, List.of(newer, older)); // reversed intentionally

    handler.handle(approveEventData());

    var order = inOrder(helpdeskClient);
    order
        .verify(helpdeskClient)
        .addComment(eq(TICKET_ID), startsWith("first"), eq(CommentType.PLAIN_TEXT), eq(config));
    order
        .verify(helpdeskClient)
        .addComment(eq(TICKET_ID), startsWith("second"), eq(CommentType.PLAIN_TEXT), eq(config));
  }

  @Test
  void postNegotiationCommentsToTicket_perResource_postsCommentToEachTicket() {
    var negotiation = createNegotiation();
    var resource1 = createResource("r-1", 1L);
    var resource2 = createResource("r-2", 2L);
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var ticket1 = new CreatedTicket("ticket-1", "1");
    var ticket2 = new CreatedTicket("ticket-2", "2");
    var post = TestUtil.post("p-1", "Hello", EntityModelPostDTO.TypeEnum.PUBLIC, null);

    stubHappyPath(negotiation, config, List.of(resource1, resource2));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket1).thenReturn(ticket2);
    stubPosts(config, List.of(post));

    handler.handle(approveEventData());

    verify(helpdeskClient).addComment(eq("ticket-1"), any(), any(), eq(config));
    verify(helpdeskClient).addComment(eq("ticket-2"), any(), any(), eq(config));
  }

  @Test
  void postNegotiationCommentsToTicket_noPosts_doesNotCallAddComment() {
    var negotiation = createNegotiation();
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket);
    stubPosts(config, List.of());

    handler.handle(approveEventData());

    verify(helpdeskClient, never()).addComment(any(), any(), any(), any());
  }

  @Test
  void postNegotiationCommentsToTicket_postWithNullAuthor_textPostedWithoutFromPrefix() {
    var negotiation = createNegotiation();
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");
    var post = TestUtil.post("p-1", "Anonymous text", EntityModelPostDTO.TypeEnum.PUBLIC, null);

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket);
    stubPosts(config, List.of(post));

    handler.handle(approveEventData());

    verify(helpdeskClient)
        .addComment(eq(TICKET_ID), eq("Anonymous text"), eq(CommentType.PLAIN_TEXT), eq(config));
  }

  @Test
  void postNegotiationCommentsToTicket_postWithNullText_skipped() {
    var negotiation = createNegotiation();
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");
    var post = TestUtil.post("p-1", null, EntityModelPostDTO.TypeEnum.PUBLIC, null);

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket);
    stubPosts(config, List.of(post));

    handler.handle(approveEventData());

    verify(helpdeskClient, never()).addComment(any(), any(), any(), any());
  }

  @Test
  void postNegotiationCommentsToTicket_addCommentThrows_ticketMappingAlreadySaved() {
    var negotiation = createNegotiation();
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");
    var post = TestUtil.post("p-1", "text", EntityModelPostDTO.TypeEnum.PUBLIC, null);

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket);
    stubPosts(config, List.of(post));
    doThrow(new RuntimeException("adapter down"))
        .when(helpdeskClient)
        .addComment(any(), any(), any(), any());

    handler.handle(approveEventData());

    verify(ticketMappingRepository).save(eq(mappingFor(ticket, "1", resource)));
  }

  @Test
  void handleStateChange_callsUpdateNegotiationStateOnAllMappedTickets() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(twoMappings());
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    verify(helpdeskClient)
        .updateNegotiationState(
            eq("ticket-1"), eq(NegotiatorNegotiationEvent.PAUSE.toState()), eq(config));
    verify(helpdeskClient)
        .updateNegotiationState(
            eq("ticket-2"), eq(NegotiatorNegotiationEvent.PAUSE.toState()), eq(config));
    verify(helpdeskClient).addSystemComment(eq("ticket-1"), eq(PAUSED_SYSTEM_COMMENT), eq(config));
    verify(helpdeskClient).addSystemComment(eq("ticket-2"), eq(PAUSED_SYSTEM_COMMENT), eq(config));
    verify(negotiatorClient, never()).getPost(any(), any(), any());
    verify(helpdeskClient, never()).addComment(any(), any(), any(), any());
  }

  @Test
  void handleStateChange_perNegotiation_singleTicketMultipleResources_callsOnce() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var mapping1 =
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "r-1"),
            "src-1",
            String.valueOf(ORG_INTERNAL_ID),
            ORG_EXT_ID);
    var mapping2 =
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "r-2"),
            "src-2",
            String.valueOf(ORG_INTERNAL_ID),
            ORG_EXT_ID);

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    verify(helpdeskClient, times(1))
        .updateNegotiationState(
            eq(TICKET_ID), eq(NegotiatorNegotiationEvent.PAUSE.toState()), eq(config));
    verify(helpdeskClient, times(1))
        .addSystemComment(eq(TICKET_ID), eq(PAUSED_SYSTEM_COMMENT), eq(config));
  }

  @Test
  void handleStateChange_noMappedTickets_doesNothing() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    verify(helpdeskClient, never()).updateNegotiationState(any(), any(), any());
  }

  @Test
  void handleStateChange_missingNegotiationId_discards() {
    JsonNode data =
        objectMapper.valueToTree(
            Map.of(
                "event",
                NegotiatorNegotiationEvent.PAUSE.name(),
                "fromState",
                NegotiatorNegotiationEvent.UNPAUSE.toState(),
                "toState",
                NegotiatorNegotiationEvent.PAUSE.toState()));

    assertDoesNotThrow(() -> handler.handle(data));

    verify(ticketMappingRepository, never()).findByIdNegotiationId(any());
    verify(helpdeskClient, never()).updateNegotiationState(any(), any(), any());
  }

  @Test
  void handleStateChange_missingToState_discards() {
    JsonNode data =
        objectMapper.valueToTree(
            Map.of(
                "negotiationId",
                NEGOTIATION_ID,
                "event",
                NegotiatorNegotiationEvent.PAUSE.name(),
                "fromState",
                NegotiatorNegotiationEvent.UNPAUSE.toState()));

    assertDoesNotThrow(() -> handler.handle(data));

    verify(ticketMappingRepository, never()).findByIdNegotiationId(any());
    verify(helpdeskClient, never()).updateNegotiationState(any(), any(), any());
  }

  @Test
  void handleStateChange_orgNotInConfig_skipsTicket() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    verify(helpdeskClient, never()).updateNegotiationState(any(), any(), any());
  }

  @Test
  void handleStateChange_adapterThrows_continuesRemainingTickets() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(twoMappings());
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    doThrow(new RuntimeException("adapter down"))
        .when(helpdeskClient)
        .updateNegotiationState(eq("ticket-1"), any(), any());

    assertDoesNotThrow(() -> handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE)));

    verify(helpdeskClient)
        .updateNegotiationState(
            eq("ticket-2"), eq(NegotiatorNegotiationEvent.PAUSE.toState()), eq(config));
    verify(helpdeskClient).addSystemComment(eq("ticket-2"), eq(PAUSED_SYSTEM_COMMENT), eq(config));
    verify(helpdeskClient, never()).addSystemComment(eq("ticket-1"), any(), any());
  }

  @Test
  void handleStateChange_stateHasTicketMapping_updatesNegotiationStateThenTicketState() {
    var toState = NegotiatorNegotiationEvent.CONCLUDE.toState();
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of(toState, "closed"));

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    handler.handle(eventData(NegotiatorNegotiationEvent.CONCLUDE));

    var order = inOrder(helpdeskClient);
    order.verify(helpdeskClient).updateNegotiationState(eq(TICKET_ID), eq(toState), eq(config));
    order
        .verify(helpdeskClient)
        .addSystemComment(eq(TICKET_ID), eq(CONCLUDED_SYSTEM_COMMENT), eq(config));
    order.verify(helpdeskClient).updateTicketState(eq(TICKET_ID), eq("closed"), eq(config));
  }

  @Test
  void handleStateChange_stateHasNoTicketMapping_doesNotUpdateTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of("ABANDONED", "closed"));

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    verify(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_ID), eq(NegotiatorNegotiationEvent.PAUSE.toState()), eq(config));
    verify(helpdeskClient).addSystemComment(eq(TICKET_ID), eq(PAUSED_SYSTEM_COMMENT), eq(config));
    verify(helpdeskClient, never()).updateTicketState(any(), any(), any());
  }

  @Test
  void handleStateChange_updateTicketStateThrows_continuesRemainingTickets() {
    var toState = NegotiatorNegotiationEvent.CONCLUDE.toState();
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of(toState, "closed"));

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(twoMappings());
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    doThrow(new RuntimeException("adapter down"))
        .when(helpdeskClient)
        .updateTicketState(eq("ticket-1"), any(), any());

    assertDoesNotThrow(() -> handler.handle(eventData(NegotiatorNegotiationEvent.CONCLUDE)));

    verify(helpdeskClient).updateTicketState(eq("ticket-2"), eq("closed"), eq(config));
  }

  @Test
  void handleStateChange_withPostId_fetchesPostOnceAndAddsSignedCommentToEachTicket() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);

    var post =
        TestUtil.post(
            "p-1",
            "Hello world",
            EntityModelPostDTO.TypeEnum.PUBLIC,
            null,
            "Alice",
            "alice@example.com");

    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(twoMappings());
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(NEGOTIATION_ID, "p-1", config)).thenReturn(post);

    handler.handle(eventDataWithPost(NegotiatorNegotiationEvent.PAUSE, "p-1"));

    verify(negotiatorClient, times(1)).getPost(NEGOTIATION_ID, "p-1", config);

    var order = inOrder(helpdeskClient);
    order
        .verify(helpdeskClient)
        .addSystemComment(eq("ticket-1"), eq(PAUSED_SYSTEM_COMMENT), eq(config));
    order
        .verify(helpdeskClient)
        .addComment(
            eq("ticket-1"),
            startsWith("From: Alice <alice@example.com>"),
            eq(CommentType.PLAIN_TEXT),
            eq(config));
    order
        .verify(helpdeskClient)
        .addSystemComment(eq("ticket-2"), eq(PAUSED_SYSTEM_COMMENT), eq(config));
    order
        .verify(helpdeskClient)
        .addComment(
            eq("ticket-2"),
            startsWith("From: Alice <alice@example.com>"),
            eq(CommentType.PLAIN_TEXT),
            eq(config));
  }

  private void stubHappyPath(
      EntityModelNegotiationDTO negotiation,
      OrganizationConfig config,
      List<EntityModelResourceWithStatusDTO> resources) {
    stubResources(negotiation, config, resources);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(attachmentForwardingService.getNegotiationPdfSummary(eq(NEGOTIATION_ID), eq(config)))
        .thenReturn(PDF_SUMMARY);
  }

  private void stubResources(
      EntityModelNegotiationDTO negotiation,
      OrganizationConfig config,
      List<EntityModelResourceWithStatusDTO> resources) {
    when(registry.findAll()).thenReturn(List.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(negotiation);
    when(negotiatorClient.getResources(NEGOTIATION_ID, config)).thenReturn(resources);
  }

  private CreateTicketRequest perResourceRequest(
      EntityModelNegotiationDTO negotiation, EntityModelResourceWithStatusDTO resource) {
    return new CreateTicketRequest(
        NegotiationTicketBodyFormatter.buildTitle(negotiation, resource.getName()),
        NEGOTIATION_STATE,
        RESOURCE_STATE,
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource),
        List.of(PDF_SUMMARY));
  }

  private CreateTicketRequest perNegotiationRequest(
      EntityModelNegotiationDTO negotiation, List<EntityModelResourceWithStatusDTO> resources) {
    return new CreateTicketRequest(
        NegotiationTicketBodyFormatter.buildTitle(negotiation, null),
        NEGOTIATION_STATE,
        RESOURCE_STATE,
        NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(negotiation, resources),
        List.of(PDF_SUMMARY));
  }

  private TicketMapping mappingFor(
      CreatedTicket ticket, String resourceKey, EntityModelResourceWithStatusDTO resource) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, ticket.ticketId(), resourceKey),
        resource.getSourceId(),
        String.valueOf(ORG_INTERNAL_ID),
        ORG_EXT_ID);
  }

  private JsonNode approveEventData() {
    return eventData(NegotiatorNegotiationEvent.APPROVE);
  }

  private JsonNode eventData(NegotiatorNegotiationEvent event) {
    return objectMapper.valueToTree(
        Map.of(
            "negotiationId",
            NEGOTIATION_ID,
            "event",
            event.name(),
            "fromState",
            "SUBMITTED",
            "toState",
            event.toState()));
  }

  private JsonNode eventDataWithPost(NegotiatorNegotiationEvent event, String postId) {
    return objectMapper.valueToTree(
        Map.of(
            "negotiationId",
            NEGOTIATION_ID,
            "event",
            event.name(),
            "fromState",
            "SUBMITTED",
            "toState",
            event.toState(),
            "postId",
            postId));
  }

  private EntityModelNegotiationDTO createNegotiation() {
    return TestUtil.negotiation(NEGOTIATION_ID, DISPLAY_ID, NEGOTIATION_STATE);
  }

  private EntityModelResourceWithStatusDTO createResource(String sourceId, Long id) {
    return TestUtil.resource(id, sourceId, "Resource " + sourceId, ORG_EXT_ID, ORG_INTERNAL_ID);
  }

  private void stubPosts(OrganizationConfig config, List<EntityModelPostDTO> posts) {
    when(negotiatorClient.getPosts(eq(NEGOTIATION_ID), eq(config))).thenReturn(posts);
  }

  private OrganizationConfig createConfig(TicketStrategy strategy) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy);
  }

  private OrganizationConfig createConfig(
      TicketStrategy strategy, Map<String, String> negotiationStateToTicketState) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy, negotiationStateToTicketState);
  }

  private TicketMapping mapping() {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "r-1"),
        "src-1",
        String.valueOf(ORG_INTERNAL_ID),
        ORG_EXT_ID);
  }

  private List<TicketMapping> twoMappings() {
    return List.of(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, "ticket-1", "r-1"),
            "src-1",
            String.valueOf(ORG_INTERNAL_ID),
            ORG_EXT_ID),
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, "ticket-2", "r-2"),
            "src-2",
            String.valueOf(ORG_INTERNAL_ID),
            ORG_EXT_ID));
  }
}
