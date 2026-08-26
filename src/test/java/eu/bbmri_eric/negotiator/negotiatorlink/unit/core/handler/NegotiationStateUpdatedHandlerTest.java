package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
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
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
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
  private static final String NEGOTIATION_STATE = "IN_PROGRESS";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_INTERNAL_ID = 10L;
  private static final String TICKET_ID = "ticket-42";
  private static final String RESOURCE_STATE = "SUBMITTED";

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private OrganizationConfigRegistry registry;
  @Mock private HelpdeskClient helpdeskClient;
  @Mock private TicketMappingRepository ticketMappingRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationStateUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationStateUpdatedHandler(
            negotiatorClient,
            registry,
            Map.of(HelpDeskType.ZAMMAD, helpdeskClient),
            ticketMappingRepository,
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
    handler.handle(eventData("PAUSE", "IN_PROGRESS", "PAUSED"));

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
  void handleApprove_noAdapterForType_skipsOrg() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(registry.findAll()).thenReturn(List.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(createNegotiation());
    when(negotiatorClient.getResources(NEGOTIATION_ID, config))
        .thenReturn(List.of(createResource("r-1", 1L)));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    var handlerWithNoAdapters =
        new NegotiationStateUpdatedHandler(
            negotiatorClient, registry, Map.of(), ticketMappingRepository, objectMapper);

    handlerWithNoAdapters.handle(approveEventData());

    verify(ticketMappingRepository, never()).save(any());
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
        .addComment(eq(TICKET_ID), startsWith("From: Alice <alice@example.com>"), eq(config));
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
    order.verify(helpdeskClient).addComment(eq(TICKET_ID), startsWith("first"), eq(config));
    order.verify(helpdeskClient).addComment(eq(TICKET_ID), startsWith("second"), eq(config));
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

    verify(helpdeskClient).addComment(eq("ticket-1"), any(), eq(config));
    verify(helpdeskClient).addComment(eq("ticket-2"), any(), eq(config));
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

    verify(helpdeskClient, never()).addComment(any(), any(), any());
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

    verify(helpdeskClient).addComment(eq(TICKET_ID), eq("Anonymous text"), eq(config));
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

    verify(helpdeskClient, never()).addComment(any(), any(), any());
  }

  @Test
  void postNegotiationCommentsToTicket_addCommentThrows_remainingPostsStillPosted() {
    var negotiation = createNegotiation();
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");

    var post1 = TestUtil.post("p-1", "first", EntityModelPostDTO.TypeEnum.PUBLIC, null);
    post1.setCreationDate(LocalDateTime.of(2024, 1, 1, 10, 0));
    var post2 = TestUtil.post("p-2", "second", EntityModelPostDTO.TypeEnum.PUBLIC, null);
    post2.setCreationDate(LocalDateTime.of(2024, 1, 2, 10, 0));

    stubHappyPath(negotiation, config, List.of(resource));
    when(helpdeskClient.createTicket(any(), eq(config))).thenReturn(ticket);
    stubPosts(config, List.of(post1, post2));
    doThrow(new RuntimeException("adapter down"))
        .when(helpdeskClient)
        .addComment(eq(TICKET_ID), startsWith("first"), eq(config));

    assertDoesNotThrow(() -> handler.handle(approveEventData()));
    verify(helpdeskClient).addComment(eq(TICKET_ID), startsWith("second"), eq(config));
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
        .addComment(any(), any(), any());

    handler.handle(approveEventData());

    verify(ticketMappingRepository).save(eq(mappingFor(ticket, "1", resource)));
  }

  /** Stubs the full happy path: negotiation, resources, and matching org config. */
  private void stubHappyPath(
      EntityModelNegotiationDTO negotiation,
      OrganizationConfig config,
      List<EntityModelResourceWithStatusDTO> resources) {
    stubResources(negotiation, config, resources);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
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
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource));
  }

  private CreateTicketRequest perNegotiationRequest(
      EntityModelNegotiationDTO negotiation, List<EntityModelResourceWithStatusDTO> resources) {
    return new CreateTicketRequest(
        NegotiationTicketBodyFormatter.buildTitle(negotiation, null),
        NEGOTIATION_STATE,
        RESOURCE_STATE,
        NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(negotiation, resources));
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
    return eventData("APPROVE", "SUBMITTED", "IN_PROGRESS");
  }

  private JsonNode eventData(String event, String fromState, String toState) {
    return objectMapper.valueToTree(
        Map.of(
            "negotiationId", NEGOTIATION_ID,
            "event", event,
            "fromState", fromState,
            "toState", toState));
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
}
