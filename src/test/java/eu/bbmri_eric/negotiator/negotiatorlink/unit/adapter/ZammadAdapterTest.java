package eu.bbmri_eric.negotiator.negotiatorlink.unit.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.ZammadAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ZammadAdapterTest {

  private static final String TICKETS_URL = "/api/v1/tickets";
  private static final String TICKET_ARTICLES_URL = "/api/v1/ticket_articles";
  private static final String LINKS_ADD_URL = "/api/v1/links/add";

  private static final String API_TOKEN = "test-api-token";
  private static final String CALLBACK_SECRET = "test-callback-secret";
  private static final String SIGNATURE_HEADER = "X-Hub-Signature";

  private WireMockServer wireMock;
  private ZammadAdapter adapter;
  private OrganizationConfig config;

  @BeforeEach
  void setUp() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
    RestClient.Builder builder =
        RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
    adapter = new ZammadAdapter(builder);
    config =
        new OrganizationConfig(
            "bbmri:DE:MHH",
            HelpDeskType.ZAMMAD,
            TicketStrategy.PER_RESOURCE,
            "http://localhost:" + wireMock.port(),
            Map.of("apiToken", API_TOKEN),
            CALLBACK_SECRET,
            Map.of("group", "Sales"));
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void getType_returnsZammadType() {
    assertThat(adapter.getType()).isEqualTo(HelpDeskType.ZAMMAD);
  }

  @Test
  void verifyCallback_validSignature_returnsTrue() throws Exception {
    byte[] body = "{\"ticket\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.set(SIGNATURE_HEADER, "sha1=" + TestUtil.hmacSha1Hex(CALLBACK_SECRET, body));

    assertThat(adapter.verifyCallback(headers, body, CALLBACK_SECRET)).isTrue();
  }

  @Test
  void verifyCallback_wrongSignature_returnsFalse() {
    byte[] body = "{\"ticket\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.set(SIGNATURE_HEADER, "sha1=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

    assertThat(adapter.verifyCallback(headers, body, CALLBACK_SECRET)).isFalse();
  }

  @Test
  void verifyCallback_missingHeader_returnsFalse() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertThat(adapter.verifyCallback(new HttpHeaders(), body, CALLBACK_SECRET)).isFalse();
  }

  @Test
  void verifyCallback_malformedHex_returnsFalse() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.set(SIGNATURE_HEADER, "sha1=not-valid-hex!!!");

    assertThat(adapter.verifyCallback(headers, body, CALLBACK_SECRET)).isFalse();
  }

  @Test
  void createTicket_perResource_sendsCorrectPayloadAndReturnsTicket() {
    wireMock.stubFor(
        post(urlEqualTo(TICKETS_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":42,\"number\":\"22042\"}")));

    var request =
        new CreateTicketRequest(
            "[NEGO-1] Dummy Collection — Access Request",
            "IN_PROGRESS",
            "SUBMITTED",
            "Ticket body");

    CreatedTicket result = adapter.createTicket(request, config);

    assertThat(result.ticketId()).isEqualTo("42");
    assertThat(result.ticketNumber()).isEqualTo("22042");

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKETS_URL))
            .withHeader("Authorization", equalTo("Token token=" + API_TOKEN))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "title": "[NEGO-1] Dummy Collection — Access Request",
                      "group": "Sales",
                      "negotiation_state": "IN_PROGRESS",
                      "negotiator_resource_state": "SUBMITTED",
                      "negotiator_resource_transition_event": null,
                      "article": {
                        "subject": "[NEGO-1] Dummy Collection — Access Request",
                        "body": "Ticket body",
                        "type": "note",
                        "internal": false
                      }
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void createTicket_perNegotiation_usesAggregateTitle() {
    wireMock.stubFor(
        post(urlEqualTo(TICKETS_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":99,\"number\":\"22099\"}")));

    var request =
        new CreateTicketRequest(
            "[NEGO-2] Resources Access Request", "IN_PROGRESS", "SUBMITTED", "Body text");

    CreatedTicket result = adapter.createTicket(request, config);

    assertThat(result.ticketId()).isEqualTo("99");

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKETS_URL))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "title": "[NEGO-2] Resources Access Request",
                      "group": "Sales",
                      "negotiation_state": "IN_PROGRESS",
                      "negotiator_resource_state": "SUBMITTED",
                      "negotiator_resource_transition_event": null,
                      "article": {
                        "subject": "[NEGO-2] Resources Access Request",
                        "body": "Body text",
                        "type": "note",
                        "internal": false
                      }
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void updateNegotiationState_sendsCorrectPut() {
    var ticketId = "42";
    wireMock.stubFor(
        put(urlEqualTo(TICKETS_URL + "/" + ticketId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    adapter.updateNegotiationState(ticketId, "CONCLUDED", config);

    wireMock.verify(
        putRequestedFor(urlEqualTo(TICKETS_URL + "/" + ticketId))
            .withHeader("Authorization", equalTo("Token token=" + API_TOKEN))
            .withRequestBody(equalToJson("{\"negotiation_state\": \"CONCLUDED\"}", true, false)));
  }

  @Test
  void updateResourceState_clearsTransitionEvent() {
    var ticketId = "42";
    wireMock.stubFor(
        put(urlEqualTo(TICKETS_URL + "/" + ticketId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    adapter.updateResourceState("42", "RESOURCE_AVAILABLE", config);

    wireMock.verify(
        putRequestedFor(urlEqualTo(TICKETS_URL + "/" + ticketId))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "negotiator_resource_state": "RESOURCE_AVAILABLE",
                      "negotiator_resource_transition_event": null
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void addComment_setsInternalFalse() {
    wireMock.stubFor(
        post(urlEqualTo(TICKET_ARTICLES_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    adapter.addComment("7", "Hello representative", config);

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKET_ARTICLES_URL))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "ticket_id": 7,
                      "body": "Hello representative",
                      "type": "note",
                      "internal": false
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void addSystemComment_setsInternalTrue() {
    wireMock.stubFor(
        post(urlEqualTo(TICKET_ARTICLES_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    adapter.addSystemComment("7", "Transition rejected", config);

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKET_ARTICLES_URL))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "ticket_id": 7,
                      "body": "Transition rejected",
                      "type": "note",
                      "internal": true
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void linkRelatedTickets_threeTickets_callsUpperTrianglePairs() {
    wireMock.stubFor(
        post(urlEqualTo(LINKS_ADD_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    var tickets =
        List.of(
            new CreatedTicket("1", "10001"),
            new CreatedTicket("2", "10002"),
            new CreatedTicket("3", "10003"));

    adapter.linkRelatedTickets(tickets, config);

    // N*(N-1)/2 = 3 calls — one per unique pair; Zammad creates a bidirectional link per call
    wireMock.verify(3, postRequestedFor(urlEqualTo(LINKS_ADD_URL)));

    wireMock.verify(
        postRequestedFor(urlEqualTo(LINKS_ADD_URL))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "link_type": "normal",
                      "link_object_source": "Ticket",
                      "link_object_source_number": "10001",
                      "link_object_target": "Ticket",
                      "link_object_target_value": 2
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void linkRelatedTickets_singleTicket_makesNoCalls() {
    adapter.linkRelatedTickets(List.of(new CreatedTicket("1", "10001")), config);
    wireMock.verify(0, postRequestedFor(urlEqualTo(LINKS_ADD_URL)));
  }

  @Test
  void parseCallback_dummyTest_returnsEmpty() {
    assertThat(adapter.parseCallback(null)).isEmpty();
  }
}
