package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationPostAddedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMappingId;
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
  @Mock private HelpdeskClient adapter;
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
            Map.of(HelpDeskType.ZAMMAD, adapter),
            ticketMappingRepository,
            originatedPostRepository,
            objectMapper);
  }

  @Test
  void handle_publicPost_fansOutToAllDistinctTickets() {
    var orgExtIdAmc = "bbmri:NL:AMC";
    var configMhh = createConfig(ORG_EXT_ID);
    var configAmc = createConfig(orgExtIdAmc);
    var mappings = List.of(mapping(TICKET_A, "1", ORG_EXT_ID), mapping(TICKET_B, "2", orgExtIdAmc));
    // resolveFetchConfig for PUBLIC: looks up ticket_mapping by negotiationId, uses first mapping
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(configMhh));
    when(registry.findByExternalId(orgExtIdAmc)).thenReturn(Optional.of(configAmc));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), any(OrganizationConfig.class)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PUBLIC));

    handler.handle(postEventData(null));

    verify(adapter).addComment(eq(TICKET_A), eq(POST_TEXT), eq(configMhh));
    verify(adapter).addComment(eq(TICKET_B), eq(POST_TEXT), eq(configAmc));
  }

  @Test
  void handle_privatePost_fansOutOnlyToOrgTickets() {
    var config = createConfig(ORG_EXT_ID);
    var mappings =
        List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID), mapping(TICKET_B, "99", "bbmri:NL:AMC"));
    // resolveFetchConfig: finds mapping for ORG_ID, resolves config via findByExternalId
    when(ticketMappingRepository.findAll()).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.empty());

    handler.handle(postEventData(ORG_ID));

    verify(adapter).addComment(eq(TICKET_A), eq(POST_TEXT), eq(config));
    verify(adapter, never()).addComment(eq(TICKET_B), any(), any());
  }

  @Test
  void handle_originatedPost_excludesOriginatingTicket() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    var originated = new OriginatedPost(POST_TEXT_HASH, ORG_ID_STR, TICKET_A);
    when(ticketMappingRepository.findAll()).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.of(originated));

    handler.handle(postEventData(ORG_ID));

    verify(adapter, never()).addComment(eq(TICKET_A), any(), any());
  }

  @Test
  void handle_noMappings_doesNothing() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findAll()).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    handler.handle(postEventData(ORG_ID));

    verify(adapter, never()).addComment(any(), any(), any());
  }

  @Test
  void handle_noConfigForPrivatePost_doesNothing() {
    when(ticketMappingRepository.findAll()).thenReturn(List.of());

    handler.handle(postEventData(ORG_ID));

    verify(negotiatorClient, never()).getPost(any(), any(), any());
    verify(adapter, never()).addComment(any(), any(), any());
  }

  @Test
  void handle_noConfigForPublicPost_doesNothing() {
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    handler.handle(postEventData(null));

    verify(negotiatorClient, never()).getPost(any(), any(), any());
    verify(adapter, never()).addComment(any(), any(), any());
  }

  @Test
  void handle_postNotFound_doesNothing() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findAll()).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config))).thenReturn(null);

    handler.handle(postEventData(ORG_ID));

    verify(ticketMappingRepository, never()).findByIdNegotiationId(any());
    verify(adapter, never()).addComment(any(), any(), any());
  }

  private JsonNode postEventData(Long orgId) {
    Map<String, Object> map = new java.util.HashMap<>();
    map.put("postId", POST_ID);
    map.put("negotiationId", NEGOTIATION_ID);
    map.put("userId", 1L);
    if (orgId != null) map.put("organizationId", orgId);
    return objectMapper.valueToTree(map);
  }

  @Test
  void handle_privatePost_withCreatedByNameAndEmail_prependsSenderHeader() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findAll()).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(
            TestUtil.post(
                POST_ID,
                POST_TEXT,
                EntityModelPostDTO.TypeEnum.PRIVATE,
                ORG_ID_STR,
                "Jane Doe",
                "jane@example.com"));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.empty());

    handler.handle(postEventData(ORG_ID));

    verify(adapter)
        .addComment(
            eq(TICKET_A), eq("From: Jane Doe <jane@example.com>\n\n" + POST_TEXT), eq(config));
  }

  @Test
  void handle_privatePost_withCreatedByNameOnly_prependsNameHeader() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findAll()).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(
            TestUtil.post(
                POST_ID,
                POST_TEXT,
                EntityModelPostDTO.TypeEnum.PRIVATE,
                ORG_ID_STR,
                "Jane Doe",
                null));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.empty());

    handler.handle(postEventData(ORG_ID));

    verify(adapter).addComment(eq(TICKET_A), eq("From: Jane Doe\n\n" + POST_TEXT), eq(config));
  }

  @Test
  void handle_privatePost_withCreatedByEmailOnly_prependsEmailHeader() {
    var config = createConfig(ORG_EXT_ID);
    var mappings = List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID));
    when(ticketMappingRepository.findAll()).thenReturn(mappings);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(POST_ID), eq(config)))
        .thenReturn(
            TestUtil.post(
                POST_ID,
                POST_TEXT,
                EntityModelPostDTO.TypeEnum.PRIVATE,
                ORG_ID_STR,
                null,
                "jane@example.com"));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(mappings);
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.empty());

    handler.handle(postEventData(ORG_ID));

    verify(adapter)
        .addComment(eq(TICKET_A), eq("From: jane@example.com\n\n" + POST_TEXT), eq(config));
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
