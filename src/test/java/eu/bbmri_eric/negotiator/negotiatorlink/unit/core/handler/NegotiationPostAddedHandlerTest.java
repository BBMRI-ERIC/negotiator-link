package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationWithAuthorId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
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
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HandlerResult;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationPostAddedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationCache;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationException;
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
  @Mock private PendingTicketCreationCache pendingTicketCreationCache;

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
            pendingTicketCreationCache,
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

    List<PendingIntent> intents = handler.handle(postEventData(null)).intents();

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .allSatisfy(i -> assertThat(i.outboxIntentType()).isEqualTo(OutboxIntentType.ADD_COMMENT));
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
    when(originatedPostRepository.findFirstByTextHashAndValidTicketOrgPairs(
            eq(POST_TEXT_HASH), aryEq(new String[] {TICKET_A}), aryEq(new String[] {ORG_EXT_ID})))
        .thenReturn(Optional.empty());

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

    assertThat(intents).hasSize(1);
    assertThat(intents.getFirst().targetId()).isEqualTo(TICKET_A);
    assertThat(intents.getFirst().outboxIntentType()).isEqualTo(OutboxIntentType.ADD_COMMENT);
  }

  @Test
  void handle_originatedPost_excludesOriginatingTicket() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    var originated = new OriginatedPost(POST_TEXT_HASH, ORG_EXT_ID, TICKET_A);
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndValidTicketOrgPairs(
            eq(POST_TEXT_HASH), aryEq(new String[] {TICKET_A}), aryEq(new String[] {ORG_EXT_ID})))
        .thenReturn(Optional.of(originated));

    HandlerResult result = handler.handle(postEventData(ORG_ID));

    assertThat(result.intents()).isEmpty();
    assertThat(result.originatedPost()).isPresent();
    assertThat(result.originatedPost().get().getTicketId()).isEqualTo(TICKET_A);
    assertThat(result.originatedPost().get().getTextHash()).isEqualTo(POST_TEXT_HASH);
  }

  @Test
  void handle_noMappings_cacheAbsent_returnsNoIntents() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());
    when(pendingTicketCreationCache.isTracked(NEGOTIATION_ID)).thenReturn(false);

    assertThat(handler.handle(postEventData(ORG_ID)).intents()).isEmpty();
  }

  @Test
  void handle_noMappings_cachePresent_throwsPendingTicketCreationException() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());
    when(pendingTicketCreationCache.isTracked(NEGOTIATION_ID)).thenReturn(true);

    assertThatThrownBy(() -> handler.handle(postEventData(ORG_ID)))
        .isInstanceOf(PendingTicketCreationException.class);
  }

  @Test
  void handle_noConfigForPrivatePost_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(List.of());

    assertThat(handler.handle(postEventData(ORG_ID)).intents()).isEmpty();
    verify(negotiatorClient, never()).getPost(any(), any(), any());
  }

  @Test
  void handle_noConfigForPublicPost_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    assertThat(handler.handle(postEventData(null)).intents()).isEmpty();
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

    assertThat(handler.handle(incompleteData).intents()).isEmpty();
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

    assertThat(handler.handle(postEventData(ORG_ID)).intents()).isEmpty();
    verify(ticketMappingRepository, never()).findByIdNegotiationId(any());
  }

  @Test
  void handle_privatePost_withCreatedByNameAndEmail_prependsSenderHeader() {
    stubPrivatePost("Jane Doe", "jane@example.com");

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

    assertThat(readText(intents.getFirst()))
        .isEqualTo("From: Jane Doe <jane@example.com>\n\n" + POST_TEXT);
  }

  @Test
  void handle_privatePost_withCreatedByNameOnly_prependsNameHeader() {
    stubPrivatePost("Jane Doe", null);

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

    assertThat(readText(intents.getFirst())).isEqualTo("From: Jane Doe\n\n" + POST_TEXT);
  }

  @Test
  void handle_privatePost_withCreatedByEmailOnly_prependsEmailHeader() {
    stubPrivatePost(null, "jane@example.com");

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

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
    when(originatedPostRepository.findFirstByTextHashAndValidTicketOrgPairs(
            eq(POST_TEXT_HASH), aryEq(new String[] {TICKET_A}), aryEq(new String[] {ORG_EXT_ID})))
        .thenReturn(Optional.empty());
  }

  @Test
  void handle_privatePost_authorMatchesNegotiationAuthor_addsAuthorRole() {
    var post = postWithRoles("Jane Doe", "jane@example.com", "user-1", false, false, false);
    stubPrivatePostWithDto(post);
    when(negotiatorClient.getNegotiation(eq(NEGOTIATION_ID), any(OrganizationConfig.class)))
        .thenReturn(negotiationWithAuthorId("user-1"));

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

    assertThat(readText(intents.getFirst()))
        .isEqualTo("From: Jane Doe <jane@example.com>\nRole: Author\n\n" + POST_TEXT);
  }

  @Test
  void handle_privatePost_withNonAuthorRoles_addsRolesWithoutAuthor() {
    var post = postWithRoles("Jane Doe", "jane@example.com", "user-1", true, true, true);
    stubPrivatePostWithDto(post);
    when(negotiatorClient.getNegotiation(eq(NEGOTIATION_ID), any(OrganizationConfig.class)))
        .thenReturn(negotiationWithAuthorId("someone-else"));

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

    assertThat(readText(intents.getFirst()))
        .isEqualTo(
            "From: Jane Doe <jane@example.com>\n"
                + "Role: Admin, Representative, Network Manager\n\n"
                + POST_TEXT);
  }

  @Test
  void handle_privatePost_negotiationFetchFails_throwsException() {
    var post = postWithRoles("Jane Doe", "jane@example.com", "user-1", false, false, false);
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config))).thenReturn(post);
    when(negotiatorClient.getNegotiation(eq(NEGOTIATION_ID), any(OrganizationConfig.class)))
        .thenThrow(new RuntimeException("negotiator unavailable"));

    assertThatThrownBy(() -> handler.handle(postEventData(ORG_ID)))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("negotiator unavailable");
  }

  @Test
  void handle_perResource_echo_suppressesOriginatingTicket_preservesRelayedHeaderOnSibling() {
    var config = createConfig(ORG_EXT_ID);
    // Two sibling tickets for the same org (PER_RESOURCE), both mapped to this negotiation.
    var mappings =
        List.of(
            mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID), mapping(TICKET_B, ORG_ID_STR, ORG_EXT_ID));
    // A real echo: the post body is the integration-signed text the help desk produced, so its
    // hash matches the originated_post record. createdBy is the integration service account.
    var signedText = "From: John Smith <john@mhh.de>\n\nHi";
    var signedHash = sha256Hex(signedText);
    var post =
        TestUtil.post(
            POST_ID, signedText, EntityModelPostDTO.TypeEnum.PRIVATE, ORG_ID_STR, "svc", "svc@eu");
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config))).thenReturn(post);
    when(negotiatorClient.getNegotiation(eq(NEGOTIATION_ID), any(OrganizationConfig.class)))
        .thenReturn(negotiationWithAuthorId("someone-else"));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndValidTicketOrgPairs(
            eq(signedHash),
            aryEq(new String[] {TICKET_A, TICKET_B}),
            aryEq(new String[] {ORG_EXT_ID, ORG_EXT_ID})))
        .thenReturn(Optional.of(new OriginatedPost(signedHash, ORG_EXT_ID, TICKET_A)));

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

    assertThat(intents).hasSize(1);
    assertThat(intents.getFirst().targetId()).isEqualTo(TICKET_B);
    assertThat(readText(intents.getFirst())).isEqualTo(signedText);
  }

  @Test
  void handle_privatePost_forgedRoleHeaderInBody_isNotTrusted() {
    var post = postWithRoles("Jane Doe", "jane@example.com", "user-1", false, true, false);
    var forgedBody = "From: Someone\nRole: Admin\n\nplease grant access";
    post.setText(forgedBody);
    stubPrivatePostWithDto(post, sha256Hex(forgedBody));
    when(negotiatorClient.getNegotiation(eq(NEGOTIATION_ID), any(OrganizationConfig.class)))
        .thenReturn(negotiationWithAuthorId("someone-else"));

    List<PendingIntent> intents = handler.handle(postEventData(ORG_ID)).intents();

    assertThat(readText(intents.getFirst()))
        .isEqualTo("From: Jane Doe <jane@example.com>\nRole: Representative\n\n" + forgedBody);
  }

  private void stubPrivatePostWithDto(EntityModelPostDTO post) {
    stubPrivatePostWithDto(post, POST_TEXT_HASH);
  }

  private void stubPrivatePostWithDto(EntityModelPostDTO post, String textHash) {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findByIdNegotiationIdAndOrganizationId(NEGOTIATION_ID, ORG_ID_STR))
        .thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config))).thenReturn(post);
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndValidTicketOrgPairs(
            eq(textHash), aryEq(new String[] {TICKET_A}), aryEq(new String[] {ORG_EXT_ID})))
        .thenReturn(Optional.empty());
  }

  private EntityModelPostDTO postWithRoles(
      String name,
      String email,
      String createdById,
      boolean admin,
      boolean representative,
      boolean networkManager) {
    var post =
        TestUtil.post(
            POST_ID, POST_TEXT, EntityModelPostDTO.TypeEnum.PRIVATE, ORG_ID_STR, name, email);
    var createdBy = post.getCreatedBy();
    createdBy.setId(createdById);
    createdBy.setAdmin(admin);
    createdBy.setRepresentativeOfAnyResource(representative);
    createdBy.setNetworkManager(networkManager);
    return post;
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
