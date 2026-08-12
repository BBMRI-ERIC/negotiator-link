package eu.bbmri_eric.negotiator.negotiatorlink.e2e;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.hmacSha1Hex;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@SpringBootTest
@AutoConfigureMockMvc
class ZammadCallbackE2ETest {

  private static final String TICKET_ID = "1";
  private static final String NEGOTIATION_ID = "neg-cb-1";
  private static final String RESOURCE_SOURCE_ID = "src-cb-1";
  private static final String RESOURCE_ID = "100";
  private static final String ORGANIZATION_ID = "42";
  private static final String POST_TEXT = "Internal Message";
  private static final String HELPDESK_ACTOR = "John Smith";

  private static final String PER_RESOURCE_ORG = "bbmri:DE:MHH";
  private static final String PER_RESOURCE_SECRET = "test-callback-secret";

  private static final String PER_NEGOTIATION_ORG = "bbmri:NL:LUMC";
  private static final String PER_NEGOTIATION_SECRET = "test-callback-secret-lumc";

  @Autowired private MockMvc mockMvc;
  @Autowired private TicketMappingRepository ticketMappingRepository;
  @Autowired private RestClient.Builder restClientBuilder;

  @MockitoBean private NegotiatorClient negotiatorClient;

  private MockRestServiceServer zammadServer;

  @BeforeEach
  void setUp() {
    ticketMappingRepository.deleteAll();
    zammadServer = MockRestServiceServer.bindTo(restClientBuilder).build();
  }

  @Test
  void postFromHelpdesk_perResourceOrg_forwardsPostToNegotiator() throws Exception {
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID),
            RESOURCE_SOURCE_ID,
            ORGANIZATION_ID,
            PER_RESOURCE_ORG));

    postCallback(PER_RESOURCE_ORG, PER_RESOURCE_SECRET, newPublicArticlePayload());

    verify(negotiatorClient)
        .createPost(
            eq(NEGOTIATION_ID),
            argThat(
                p ->
                    POST_TEXT.equals(p.getText())
                        && PostCreateDTO.TypeEnum.PRIVATE.equals(p.getType())
                        && HELPDESK_ACTOR.equals(p.getHelpdeskActor())));
    zammadServer.verify();
  }

  @Test
  void postFromHelpdesk_perNegotiationOrg_forwardsPostToNegotiator() throws Exception {
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "200"),
            "src-cb-2a",
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "201"),
            "src-cb-2b",
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));

    postCallback(PER_NEGOTIATION_ORG, PER_NEGOTIATION_SECRET, newPublicArticlePayload());

    verify(negotiatorClient)
        .createPost(
            eq(NEGOTIATION_ID),
            argThat(
                p ->
                    POST_TEXT.equals(p.getText())
                        && PostCreateDTO.TypeEnum.PRIVATE.equals(p.getType())
                        && HELPDESK_ACTOR.equals(p.getHelpdeskActor())));
    zammadServer.verify();
  }

  @Test
  void resourceTransition_perResourceOrg_sendsResourceEventToNegotiator() throws Exception {
    String event = "MARK_AS_CHECKING_AVAILABILITY";

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID),
            RESOURCE_SOURCE_ID,
            ORGANIZATION_ID,
            PER_RESOURCE_ORG));

    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID))
        .thenReturn(Set.of(event));

    postCallback(PER_RESOURCE_ORG, PER_RESOURCE_SECRET, resourceTransitionOnlyPayload());

    verify(negotiatorClient)
        .sendResourceEvent(eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), eq(event), isNull());
    zammadServer.verify();
  }

  @Test
  void resourceTransition_perNegotiationOrg_sendsBulkResourceEventToNegotiator() throws Exception {
    String resourceSourceId1 = "src-cb-4a";
    String resourceSourceId2 = "src-cb-4b";
    Long resourceId1 = 400L;
    Long resourceId2 = 401L;
    String event = "MARK_AS_CHECKING_AVAILABILITY";

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, String.valueOf(resourceId1)),
            resourceSourceId1,
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, String.valueOf(resourceId2)),
            resourceSourceId2,
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));

    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, resourceSourceId1))
        .thenReturn(Set.of(event));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, resourceSourceId2))
        .thenReturn(Set.of(event));

    postCallback(PER_NEGOTIATION_ORG, PER_NEGOTIATION_SECRET, resourceTransitionOnlyPayload());

    verify(negotiatorClient)
        .sendBulkResourceEvent(
            eq(NEGOTIATION_ID),
            argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(resourceId1, resourceId2))),
            eq(event),
            isNull());
    zammadServer.verify();
  }

  private void postCallback(String orgExternalId, String secret, String body) throws Exception {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    String sig = "sha1=" + hmacSha1Hex(secret, bodyBytes);

    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", orgExternalId)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", sig)
                .header("X-Zammad-Delivery", orgExternalId + "-" + System.nanoTime())
                .content(bodyBytes))
        .andExpect(status().isOk());
  }

  private static String newPublicArticlePayload() {
    return """
        {
          "ticket": {
            "id": 1,
            "negotiator_resource_transition_event": ""
          },
          "article": {
            "body": "%s",
            "from": "%s",
            "internal": false
          }
        }
        """
        .formatted(POST_TEXT, HELPDESK_ACTOR);
  }

  private static String resourceTransitionOnlyPayload() {
    return """
        {
          "ticket": {
            "id": 1,
            "negotiator_resource_transition_event": "MARK_AS_CHECKING_AVAILABILITY"
          },
          "article": {}
        }
        """;
  }
}
