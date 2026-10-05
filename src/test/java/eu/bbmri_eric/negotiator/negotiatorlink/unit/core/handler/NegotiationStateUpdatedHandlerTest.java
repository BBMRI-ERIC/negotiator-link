package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreateTicketPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreateTicketPayload.ResourceMappingRef;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.LinkTicketsPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class NegotiationStateUpdatedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String DISPLAY_ID = "NEGO-1";
  private static final String NEGOTIATION_STATE = NegotiatorNegotiationEvent.APPROVE.toState();
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_INTERNAL_ID = 10L;
  private static final String TICKET_ID = "ticket-42";
  private static final String PAUSED_SYSTEM_COMMENT = "Negotiation status changed to 'Paused'";
  private static final String CONCLUDED_SYSTEM_COMMENT =
      "Negotiation status changed to 'Concluded'";

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private OrganizationConfigRegistry registry;
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
            ticketMappingRepository,
            attachmentForwardingService,
            objectMapper);
  }

  @Test
  void handle_perResource_multipleResources_emitsCreateTicketPerResourceAndLink() {
    var resource1 = createResource("r-1", 1L);
    var resource2 = createResource("r-2", 2L);
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var negotiation = createNegotiation();
    stubHappyPath(negotiation, config, List.of(resource1, resource2));

    List<PendingIntent> intents = handler.handle(approveEventData());

    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .containsExactly(
            OutboxIntentType.CREATE_TICKET,
            OutboxIntentType.CREATE_TICKET,
            OutboxIntentType.LINK_TICKETS);
    assertThat(readCreate(intents.getFirst()))
        .isEqualTo(
            createTicketPayload(
                NEGOTIATION_ID,
                NegotiationTicketBodyFormatter.buildTitle(negotiation, resource1.getName()),
                NEGOTIATION_STATE,
                "SUBMITTED",
                NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                    negotiation, resource1),
                List.of(),
                List.of(new ResourceMappingRef("1", "r-1", String.valueOf(ORG_INTERNAL_ID))),
                List.of()));
    assertThat(readCreate(intents.get(1)))
        .isEqualTo(
            createTicketPayload(
                NEGOTIATION_ID,
                NegotiationTicketBodyFormatter.buildTitle(negotiation, resource2.getName()),
                NEGOTIATION_STATE,
                "SUBMITTED",
                NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                    negotiation, resource2),
                List.of(),
                List.of(new ResourceMappingRef("2", "r-2", String.valueOf(ORG_INTERNAL_ID))),
                List.of()));
    LinkTicketsPayload link = readLink(intents.get(2));
    assertThat(link.resourceSourceIds()).containsExactly("r-1", "r-2");
    assertThat(link.negotiationId()).isEqualTo(NEGOTIATION_ID);
  }

  @Test
  void handle_perNegotiation_multipleResources_emitsSingleCreateTicketForAllResources() {
    var resource1 = createResource("r-1", 1L);
    var resource2 = createResource("r-2", 2L);
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var negotiation = createNegotiation();
    stubHappyPath(negotiation, config, List.of(resource1, resource2));

    List<PendingIntent> intents = handler.handle(approveEventData());

    assertThat(intents).hasSize(1);
    assertThat(readCreate(intents.getFirst()))
        .isEqualTo(
            createTicketPayload(
                NEGOTIATION_ID,
                NegotiationTicketBodyFormatter.buildTitle(negotiation, null),
                NEGOTIATION_STATE,
                "SUBMITTED",
                NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(
                    negotiation, List.of(resource1, resource2)),
                List.of(),
                List.of(
                    new ResourceMappingRef("1", "r-1", String.valueOf(ORG_INTERNAL_ID)),
                    new ResourceMappingRef("2", "r-2", String.valueOf(ORG_INTERNAL_ID))),
                List.of()));
  }

  @Test
  void handle_perResource_singleResource_doesNotEmitLink() {
    var resource = createResource("r-1", 1L);
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    stubHappyPath(createNegotiation(), config, List.of(resource));

    List<PendingIntent> intents = handler.handle(approveEventData());

    assertThat(intents).hasSize(1);
    assertThat(intents.getFirst().outboxIntentType()).isEqualTo(OutboxIntentType.CREATE_TICKET);
  }

  @Test
  void handle_orgNotInConfig_emitsNoIntents() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    stubResources(createNegotiation(), config, List.of(createResource("r-1", 1L)));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    assertThat(handler.handle(approveEventData())).isEmpty();
  }

  @Test
  void handle_nonApproveEvent_doesNotEmitCreateTicket() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    List<PendingIntent> intents = handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .doesNotContain(OutboxIntentType.CREATE_TICKET);
    verify(negotiatorClient, never()).getResources(any(), any());
  }

  @Test
  void handle_noOrgsConfigured_emitsNoIntents() {
    when(registry.findAll()).thenReturn(List.of());

    assertThat(handler.handle(approveEventData())).isEmpty();
    verify(negotiatorClient, never()).getNegotiation(any(), any());
  }

  @Test
  void handle_noResources_emitsNoIntents() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(registry.findAll()).thenReturn(List.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(createNegotiation());
    when(negotiatorClient.getResources(NEGOTIATION_ID, config)).thenReturn(List.of());

    assertThat(handler.handle(approveEventData())).isEmpty();
  }

  @Test
  void handle_allOrgsReturnForbidden_emitsNoIntents() {
    var config1 = createConfig(TicketStrategy.PER_RESOURCE);
    var config2 = createConfig(TicketStrategy.PER_NEGOTIATION);
    when(registry.findAll()).thenReturn(List.of(config1, config2));
    when(negotiatorClient.getNegotiation(any(), any()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

    assertThat(handler.handle(approveEventData())).isEmpty();
    verify(negotiatorClient, never()).getResources(any(), any());
  }

  @Test
  void handle_withPublicAttachments_createTicketPayloadCarriesAttachmentIds() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var resource = createResource("r-1", 1L);
    var resolved =
        new ResolvedAttachment(
            "a-1", new Attachment("public.txt", "text/plain", "bytes".getBytes()));
    stubHappyPath(createNegotiation(), config, List.of(resource));
    when(attachmentForwardingService.resolveForwardablePayloadAttachments(NEGOTIATION_ID, config))
        .thenReturn(List.of(resolved));

    List<PendingIntent> intents = handler.handle(approveEventData());

    assertThat(readCreate(intents.getFirst()).attachmentIds()).containsExactly("a-1");
  }

  @Test
  void handle_withPosts_createTicketPayloadWithSignedCommentsInOrder() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var resource = createResource("r-1", 1L);
    var older = TestUtil.post("p-1", "first", EntityModelPostDTO.TypeEnum.PUBLIC, null);
    older.setCreationDate(LocalDateTime.of(2024, 1, 1, 10, 0));
    var newer = TestUtil.post("p-2", "second", EntityModelPostDTO.TypeEnum.PUBLIC, null);
    newer.setCreationDate(LocalDateTime.of(2024, 1, 2, 10, 0));
    stubHappyPath(createNegotiation(), config, List.of(resource));
    stubPosts(config, List.of(newer, older)); // reversed intentionally

    List<PendingIntent> intents = handler.handle(approveEventData());

    List<String> postTexts = readCreate(intents.getFirst()).postTexts();
    assertThat(postTexts).hasSize(2);
    assertThat(postTexts.getFirst()).startsWith("first");
    assertThat(postTexts.get(1)).startsWith("second");
  }

  @Test
  void handle_postWithNullText_excluded() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var resource = createResource("r-1", 1L);
    var post = TestUtil.post("p-1", null, EntityModelPostDTO.TypeEnum.PUBLIC, null);
    stubHappyPath(createNegotiation(), config, List.of(resource));
    stubPosts(config, List.of(post));

    List<PendingIntent> intents = handler.handle(approveEventData());

    assertThat(readCreate(intents.getFirst()).postTexts()).isEmpty();
  }

  @Test
  void handle_stateChange_emitsSetNegotiationStateAndSystemCommentPerTicket() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(twoMappings());
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    List<PendingIntent> intents = handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    assertThat(ticketTargets(intents, OutboxIntentType.SET_NEGOTIATION_STATE))
        .containsExactlyInAnyOrder("ticket-1", "ticket-2");
    assertThat(ticketTargets(intents, OutboxIntentType.ADD_SYSTEM_COMMENT))
        .containsExactlyInAnyOrder("ticket-1", "ticket-2");
    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .doesNotContain(OutboxIntentType.ADD_COMMENT);
    assertThat(readState(firstOf(intents, OutboxIntentType.SET_NEGOTIATION_STATE)).value())
        .isEqualTo(NegotiatorNegotiationEvent.PAUSE.toState());
    assertThat(readSystemComment(firstOf(intents, OutboxIntentType.ADD_SYSTEM_COMMENT)).message())
        .isEqualTo(PAUSED_SYSTEM_COMMENT);
    verify(negotiatorClient, never()).getPost(any(), any(), any());
  }

  @Test
  void handle_stateChange_perNegotiation_singleTicketMultipleResources_emitsOnce() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var mapping1 = mappingFor(TICKET_ID, "r-1", "src-1");
    var mapping2 = mappingFor(TICKET_ID, "r-2", "src-2");
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    List<PendingIntent> intents = handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    assertThat(ticketTargets(intents, OutboxIntentType.SET_NEGOTIATION_STATE))
        .containsExactly(TICKET_ID);
    assertThat(ticketTargets(intents, OutboxIntentType.ADD_SYSTEM_COMMENT))
        .containsExactly(TICKET_ID);
  }

  @Test
  void handle_stateChange_noMappedTickets_emitsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    assertThat(handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE))).isEmpty();
  }

  static java.util.stream.Stream<Arguments> incompleteStateEventData() {
    var event = NegotiatorNegotiationEvent.PAUSE;
    return java.util.stream.Stream.of(
        Arguments.of("missing negotiationId", null, event.toState(), event.name()),
        Arguments.of("missing toState", NEGOTIATION_ID, null, event.name()),
        Arguments.of("missing event", NEGOTIATION_ID, event.toState(), null),
        Arguments.of("missing all", null, null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("incompleteStateEventData")
  void handle_incompleteStateEvent_returnsNoIntents(
      String label, String negotiationId, String toState, String eventName) {
    var data = buildStateEventData(negotiationId, toState, eventName);

    assertThat(handler.handle(data)).isEmpty();
    verify(ticketMappingRepository, never()).findByIdNegotiationId(any());
  }

  @Test
  void handle_stateChange_orgNotInConfig_skipsTicket() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    assertThat(handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE))).isEmpty();
  }

  @Test
  void handle_stateChange_stateHasTicketMapping_emitsSetNegotiationStateThenTicketState() {
    var toState = NegotiatorNegotiationEvent.CONCLUDE.toState();
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of(toState, "closed"));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    List<PendingIntent> intents = handler.handle(eventData(NegotiatorNegotiationEvent.CONCLUDE));

    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .containsExactly(
            OutboxIntentType.SET_NEGOTIATION_STATE,
            OutboxIntentType.ADD_SYSTEM_COMMENT,
            OutboxIntentType.SET_TICKET_STATE);
    assertThat(readSystemComment(firstOf(intents, OutboxIntentType.ADD_SYSTEM_COMMENT)).message())
        .isEqualTo(CONCLUDED_SYSTEM_COMMENT);
    assertThat(readState(firstOf(intents, OutboxIntentType.SET_TICKET_STATE)).value())
        .isEqualTo("closed");
  }

  @Test
  void handle_stateChange_stateHasNoTicketMapping_doesNotEmitSetTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of("ABANDONED", "closed"));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    List<PendingIntent> intents = handler.handle(eventData(NegotiatorNegotiationEvent.PAUSE));

    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .doesNotContain(OutboxIntentType.SET_TICKET_STATE);
  }

  @Test
  void handle_stateChange_withPostId_fetchesPostOnceAndEmitsCommentPerTicket() {
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

    List<PendingIntent> intents =
        handler.handle(eventDataWithPost(NegotiatorNegotiationEvent.PAUSE, "p-1"));

    verify(negotiatorClient, times(1)).getPost(NEGOTIATION_ID, "p-1", config);
    assertThat(ticketTargets(intents, OutboxIntentType.ADD_COMMENT))
        .containsExactlyInAnyOrder("ticket-1", "ticket-2");
    assertThat(readComment(firstOf(intents, OutboxIntentType.ADD_COMMENT)).text())
        .startsWith("From: Alice <alice@example.com>");
  }

  private CreateTicketPayload createTicketPayload(
      String negotiationId,
      String title,
      String negotiationState,
      String resourceState,
      String ticketBody,
      List<String> attachmentIds,
      List<ResourceMappingRef> resources,
      List<String> postTexts) {
    return new CreateTicketPayload(
        negotiationId,
        title,
        negotiationState,
        resourceState,
        ticketBody,
        attachmentIds,
        resources,
        postTexts);
  }

  private List<String> ticketTargets(List<PendingIntent> intents, OutboxIntentType type) {
    return intents.stream()
        .filter(i -> i.outboxIntentType() == type)
        .map(PendingIntent::targetId)
        .toList();
  }

  private PendingIntent firstOf(List<PendingIntent> intents, OutboxIntentType type) {
    return intents.stream().filter(i -> i.outboxIntentType() == type).findFirst().orElseThrow();
  }

  private CreateTicketPayload readCreate(PendingIntent intent) {
    return read(intent, CreateTicketPayload.class);
  }

  private LinkTicketsPayload readLink(PendingIntent intent) {
    return read(intent, LinkTicketsPayload.class);
  }

  private SetStatePayload readState(PendingIntent intent) {
    return read(intent, SetStatePayload.class);
  }

  private AddSystemCommentPayload readSystemComment(PendingIntent intent) {
    return read(intent, AddSystemCommentPayload.class);
  }

  private AddCommentPayload readComment(PendingIntent intent) {
    return read(intent, AddCommentPayload.class);
  }

  private <T> T read(PendingIntent intent, Class<T> type) {
    try {
      return objectMapper.readValue(intent.payloadJson(), type);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

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

  private JsonNode approveEventData() {
    return eventData(NegotiatorNegotiationEvent.APPROVE);
  }

  private JsonNode eventData(NegotiatorNegotiationEvent event) {
    return buildStateEventData(NEGOTIATION_ID, event.toState(), event.name());
  }

  private JsonNode buildStateEventData(String negotiationId, String toState, String eventName) {
    Map<String, Object> map = new java.util.HashMap<>();
    if (negotiationId != null) map.put("negotiationId", negotiationId);
    if (toState != null) map.put("toState", toState);
    if (eventName != null) map.put("event", eventName);
    return objectMapper.valueToTree(map);
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
    when(negotiatorClient.getPosts(NEGOTIATION_ID, config)).thenReturn(posts);
  }

  private OrganizationConfig createConfig(TicketStrategy strategy) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy);
  }

  private OrganizationConfig createConfig(
      TicketStrategy strategy, Map<String, String> negotiationStateToTicketState) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy, negotiationStateToTicketState);
  }

  private TicketMapping mapping() {
    return mappingFor(TICKET_ID, "r-1", "src-1");
  }

  private TicketMapping mappingFor(String ticketId, String resourceId, String sourceId) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, ticketId, resourceId),
        sourceId,
        String.valueOf(ORG_INTERNAL_ID),
        ORG_EXT_ID);
  }

  private List<TicketMapping> twoMappings() {
    return List.of(mappingFor("ticket-1", "r-1", "src-1"), mappingFor("ticket-2", "r-2", "src-2"));
  }
}
