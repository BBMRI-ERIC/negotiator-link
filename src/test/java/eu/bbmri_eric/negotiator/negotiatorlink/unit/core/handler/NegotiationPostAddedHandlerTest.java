package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationPostAddedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.IntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
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

@ExtendWith(MockitoExtension.class)
class NegotiationPostAddedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String POST_ID = "post-99";
  private static final String POST_TEXT = "Hello from Negotiator";
  private static final String POST_TEXT_HASH =
      "2b4918dca9353d2d5deb79a7c1f0d780c9b872e4bbd320e635269d06ebd5cc46";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_ID = 42L;
  private static final String ORG_ID_STR = "42";
  private static final String TICKET_A = "ticket-A";
  private static final String TICKET_B = "ticket-B";

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private OrganizationConfigRegistry registry;
  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedPostRepository originatedPostRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationPostAddedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationPostAddedHandler(
            negotiatorClient,
            registry,
            ticketMappingRepository,
            originatedPostRepository,
            objectMapper);
  }

  @Test
  void handle_publicPost_fansOutToAllDistinctTickets() {
    var orgExtIdAmc = "bbmri:NL:AMC";
    var configMhh = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, "1", ORG_EXT_ID), mapping(TICKET_B, "2", orgExtIdAmc));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(configMhh));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), any(OrganizationConfig.class)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PUBLIC));

    List<PendingIntent> intents = handler.handle(postEventData(null));

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .allSatisfy(i -> assertThat(i.intentType()).isEqualTo(IntentType.ADD_COMMENT));
    assertThat(intents).extracting(PendingIntent::targetId).containsExactly(TICKET_A, TICKET_B);
    assertThat(intents)
        .extracting(PendingIntent::organizationExtId)
        .containsExactly(ORG_EXT_ID, orgExtIdAmc);
    assertThat(readText(intents.getFirst())).isEqualTo(POST_TEXT);
    assertThat(readText(intents.getLast())).isEqualTo(POST_TEXT);
  }

  @Test
  void handle_privatePost_fansOutOnlyToOrgTickets() {
    var config = createConfig(ORG_EXT_ID);
    var mappings =
        List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID), mapping(TICKET_B, "99", "bbmri:NL:AMC"));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID)));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.empty());

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID));

    assertThat(intents).hasSize(1);
    assertThat(intents.getFirst().targetId()).isEqualTo(TICKET_A);
    assertThat(intents.getFirst().intentType()).isEqualTo(IntentType.ADD_COMMENT);
  }

  @Test
  void handle_originatedPost_excludesOriginatingTicket() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    var originated = new OriginatedPost(POST_TEXT_HASH, ORG_ID_STR, TICKET_A);
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.of(originated));

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID));

    assertThat(intents).isEmpty();
    verify(originatedPostRepository).deleteById(any());
  }

  @Test
  void handle_noMappings_returnsNoIntents() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    assertThat(handler.handle(postEventData(ORG_ID))).isEmpty();
  }

  @Test
  void handle_noConfigForPrivatePost_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(List.of());

    assertThat(handler.handle(postEventData(ORG_ID))).isEmpty();
    verify(negotiatorClient, never()).getPost(any(), any(), any());
  }

  @Test
  void handle_noConfigForPublicPost_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    assertThat(handler.handle(postEventData(null))).isEmpty();
    verify(negotiatorClient, never()).getPost(any(), any(), any());
  }

  static java.util.stream.Stream<Arguments> incompleteEventData() {
    return java.util.stream.Stream.of(
        Arguments.of("missing postId", null, NEGOTIATION_ID),
        Arguments.of("missing negotiationId", POST_ID, null),
        Arguments.of("missing both", null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("incompleteEventData")
  void handle_incompleteEvent_returnsNoIntents(String label, String postId, String negotiationId) {
    var incompleteData = buildEventData(postId, negotiationId, null);

    assertThat(handler.handle(incompleteData)).isEmpty();
    verify(negotiatorClient, never()).getPost(any(), any(), any());
  }

  @Test
  void handle_postNotFound_returnsNoIntents() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config))).thenReturn(null);

    assertThat(handler.handle(postEventData(ORG_ID))).isEmpty();
    verify(ticketMappingRepository, never()).findByIdNegotiationId(any());
  }

  @Test
  void handle_privatePost_withCreatedByNameAndEmail_prependsSenderHeader() {
    stubPrivatePost("Jane Doe", "jane@example.com");

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID));

    assertThat(readText(intents.getFirst()))
        .isEqualTo("From: Jane Doe <jane@example.com>\n\n" + POST_TEXT);
  }

  @Test
  void handle_privatePost_withCreatedByNameOnly_prependsNameHeader() {
    stubPrivatePost("Jane Doe", null);

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID));

    assertThat(readText(intents.getFirst())).isEqualTo("From: Jane Doe\n\n" + POST_TEXT);
  }

  @Test
  void handle_privatePost_withCreatedByEmailOnly_prependsEmailHeader() {
    stubPrivatePost(null, "jane@example.com");

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID));

    assertThat(readText(intents.getFirst())).isEqualTo("From: jane@example.com\n\n" + POST_TEXT);
  }

  private void stubPrivatePost(String name, String email) {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(
            TestUtil.post(
                POST_ID, POST_TEXT, EntityModelPostDTO.TypeEnum.PRIVATE, ORG_ID_STR, name, email));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.empty());
  }

  private String readText(PendingIntent intent) {
    try {
      return objectMapper.readValue(intent.payloadJson(), AddCommentPayload.class).text();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private JsonNode postEventData(Long orgId) {
    return buildEventData(POST_ID, NEGOTIATION_ID, orgId);
  }

  private JsonNode buildEventData(String postId, String negotiationId, Long orgId) {
    Map<String, Object> map = new java.util.HashMap<>();
    if (postId != null) map.put("postId", postId);
    if (negotiationId != null) map.put("negotiationId", negotiationId);
    map.put("userId", 1L);
    if (orgId != null) map.put("organizationId", orgId);
    return objectMapper.valueToTree(map);
  }

  private EntityModelPostDTO createPost(EntityModelPostDTO.TypeEnum type) {
    return TestUtil.post(POST_ID, POST_TEXT, type, ORG_ID_STR);
  }

  private TicketMapping mapping(String ticketId, String orgId, String orgExtId) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, ticketId, "r-1"), "src-1", orgId, orgExtId);
  }

  private OrganizationConfig createConfig(String orgExtId) {
    return TestUtil.organizationConfig(orgExtId, TicketStrategy.PER_RESOURCE);
  }
}
