package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationPostAddedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
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
  @Mock private HelpDeskAdapter adapter;
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
    var configMhh = createConfig("bbmri:DE:MHH");
    var configAmc = createConfig("bbmri:NL:AMC");
    when(negotiatorClient.getPostText(POST_ID))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PUBLIC));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(
            List.of(
                mapping(TICKET_A, "1", "bbmri:DE:MHH"), mapping(TICKET_B, "2", "bbmri:NL:AMC")));
    when(registry.findByExternalId("bbmri:DE:MHH")).thenReturn(Optional.of(configMhh));
    when(registry.findByExternalId("bbmri:NL:AMC")).thenReturn(Optional.of(configAmc));

    handler.handle(postEventData(null));

    verify(adapter).addComment(eq(TICKET_A), eq(POST_TEXT), eq(configMhh));
    verify(adapter).addComment(eq(TICKET_B), eq(POST_TEXT), eq(configAmc));
  }

  @Test
  void handle_privatePost_fansOutOnlyToOrgTickets() {
    var config = createConfig(ORG_EXT_ID);
    when(negotiatorClient.getPostText(POST_ID))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(
            List.of(
                mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID),
                mapping(TICKET_B, "99", "bbmri:NL:AMC")));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.empty());

    handler.handle(postEventData(ORG_ID));

    verify(adapter).addComment(eq(TICKET_A), eq(POST_TEXT), eq(config));
    verify(adapter, never()).addComment(eq(TICKET_B), any(), any());
  }

  @Test
  void handle_originatedPost_excludesOriginatingTicket() {
    var originated = new OriginatedPost(POST_TEXT_HASH, ORG_ID_STR, TICKET_A);
    when(negotiatorClient.getPostText(POST_ID))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping(TICKET_A, ORG_ID_STR, ORG_EXT_ID)));
    when(originatedPostRepository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
            eq(POST_TEXT_HASH), eq(ORG_ID_STR)))
        .thenReturn(Optional.of(originated));

    handler.handle(postEventData(ORG_ID));

    verify(adapter, never()).addComment(eq(TICKET_A), any(), any());
    verify(originatedPostRepository).delete(eq(originated));
  }

  @Test
  void handle_noMappings_doesNothing() {
    when(negotiatorClient.getPostText(POST_ID))
        .thenReturn(createPost(EntityModelPostDTO.TypeEnum.PRIVATE));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID)).thenReturn(List.of());

    handler.handle(postEventData(ORG_ID));

    verify(adapter, never()).addComment(any(), any(), any());
  }

  @Test
  void handle_postNotFound_doesNothing() {
    when(negotiatorClient.getPostText(POST_ID)).thenReturn(null);

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
