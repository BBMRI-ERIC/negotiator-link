package eu.bbmri_eric.negotiator.negotiatorlink.unit.adapter.zammad;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.zammad.ZammadClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorCredentials;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ZammadClientTest {

  private static final String TICKETS_URL = "/api/v1/tickets";
  private static final String TICKET_ARTICLES_URL = "/api/v1/ticket_articles";
  private static final String LINKS_ADD_URL = "/api/v1/links/add";

  private static final String API_TOKEN = "test-api-token";
  private static final String CALLBACK_SECRET = "test-callback-secret";

  private WireMockServer wireMock;
  private ZammadClient client;
  private OrganizationConfig config;

  @BeforeEach
  void setUp() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
    RestClient.Builder builder =
        RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
    client = new ZammadClient(builder);
    config =
        new OrganizationConfig(
            "bbmri:DE:MHH",
            HelpdeskType.ZAMMAD,
            TicketStrategy.PER_RESOURCE,
            "http://localhost:" + wireMock.port(),
            Map.of("apiToken", API_TOKEN),
            CALLBACK_SECRET,
            Map.of("group", "Sales", "botEmail", "negotiator-link@mhh.de"),
            Map.of(),
            new NegotiatorCredentials("test-client-id", "test-client-secret"));
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void getType_returnsZammadType() {
    assertThat(client.getType()).isEqualTo(HelpdeskType.ZAMMAD);
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
            "[NEGO-1] Access Request — Dummy Collection",
            "IN_PROGRESS",
            "SUBMITTED",
            "Ticket body");

    CreatedTicket result = client.createTicket(request, config);

    assertThat(result.ticketId()).isEqualTo("42");
    assertThat(result.ticketNumber()).isEqualTo("22042");

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKETS_URL))
            .withHeader("Authorization", equalTo("Token token=" + API_TOKEN))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "title": "[NEGO-1] Access Request — Dummy Collection",
                      "group": "Sales",
                      "negotiation_state": "IN_PROGRESS",
                      "negotiator_resource_state": "SUBMITTED",
                      "negotiator_resource_transition_event": null,
                      "customer": "negotiator-link@mhh.de",
                      "article": {
                        "subject": "[NEGO-1] Access Request — Dummy Collection",
                        "body": "Ticket body",
                        "content_type" : "text/html",
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

    CreatedTicket result = client.createTicket(request, config);

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
                      "customer": "negotiator-link@mhh.de",
                      "article": {
                        "subject": "[NEGO-2] Resources Access Request",
                        "body": "Body text",
                        "content_type" : "text/html",
                        "type": "note",
                        "internal": false
                      }
                    }
                    """,
                    true,
                    false)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCreateTicketResponses")
  void createTicket_invalidResponse_throwsIllegalStateException(
      String description, Integer status, String responseBody) {
    var stub = aResponse().withStatus(status).withHeader("Content-Type", "application/json");
    if (responseBody != null) {
      stub = stub.withBody(responseBody);
    }
    wireMock.stubFor(post(urlEqualTo(TICKETS_URL)).willReturn(stub));

    var request =
        new CreateTicketRequest("[NEGO-1] Access Request", "IN_PROGRESS", "SUBMITTED", "Body");

    assertThatThrownBy(() -> client.createTicket(request, config))
        .isInstanceOf(IllegalStateException.class);
  }

  static Stream<Arguments> invalidCreateTicketResponses() {
    return Stream.of(
        Arguments.of("empty response body", 201, null),
        Arguments.of("missing id", 201, "{\"number\":\"22042\"}"),
        Arguments.of("missing number", 201, "{\"id\":42}"));
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

    client.updateNegotiationState(ticketId, "CONCLUDED", config);

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

    client.updateResourceState("42", "RESOURCE_AVAILABLE", config);

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
  void updateTicketState_sendsCorrectPut() {
    var ticketId = "42";
    wireMock.stubFor(
        put(urlEqualTo(TICKETS_URL + "/" + ticketId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    client.updateTicketState(ticketId, "closed", config);

    wireMock.verify(
        putRequestedFor(urlEqualTo(TICKETS_URL + "/" + ticketId))
            .withHeader("Authorization", equalTo("Token token=" + API_TOKEN))
            .withRequestBody(equalToJson("{\"state\": \"closed\"}", true, false)));
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

    client.addComment("7", "Hello representative", config);

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

    client.addSystemComment("7", "Transition rejected", config);

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

    client.linkRelatedTickets(tickets, config);

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
    client.linkRelatedTickets(List.of(new CreatedTicket("1", "10001")), config);
    wireMock.verify(0, postRequestedFor(urlEqualTo(LINKS_ADD_URL)));
  }
}
