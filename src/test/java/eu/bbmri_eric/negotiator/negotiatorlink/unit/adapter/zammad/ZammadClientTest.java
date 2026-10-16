package eu.bbmri_eric.negotiator.negotiatorlink.unit.adapter.zammad;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.zammad.ZammadClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorCredentials;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import java.nio.charset.StandardCharsets;
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
  private static final String GET_TICKET_URL = "/api/v1/tickets/{id}";

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
            Map.of(),
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
            "Ticket body",
            List.of());

    String result = client.createTicket(request, config);

    assertThat(result).isEqualTo("42");

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
            "[NEGO-2] Resources Access Request",
            "IN_PROGRESS",
            "SUBMITTED",
            "Body text",
            List.of());

    String result = client.createTicket(request, config);

    assertThat(result).isEqualTo("99");

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
        new CreateTicketRequest(
            "[NEGO-1] Access Request", "IN_PROGRESS", "SUBMITTED", "Body", List.of());

    assertThatThrownBy(() -> client.createTicket(request, config))
        .isInstanceOf(IllegalStateException.class);
  }

  static Stream<Arguments> invalidCreateTicketResponses() {
    return Stream.of(
        Arguments.of("empty response body", 201, null),
        Arguments.of("missing id", 201, "{\"number\":\"22042\"}"));
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

    client.addComment("7", "Hello representative", CommentType.PLAIN_TEXT, config);

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKET_ARTICLES_URL))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "ticket_id": 7,
                      "body": "Hello representative",
                      "content_type": "text/plain",
                      "type": "note",
                      "internal": false
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void addComment_withAttachments_postsBase64EncodedFilesOnArticle() {
    wireMock.stubFor(
        post(urlEqualTo(TICKET_ARTICLES_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    var attachment =
        new Attachment("requestor.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
    client.addComment(
        "7",
        "New negotiation-level attachments added by requestor. See the files below.",
        CommentType.PLAIN_TEXT,
        List.of(attachment),
        config);

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKET_ARTICLES_URL))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "ticket_id": 7,
                      "body": "New negotiation-level attachments added by requestor. See the files below.",
                      "content_type": "text/plain",
                      "type": "note",
                      "internal": false,
                      "attachments": [
                        {"filename": "requestor.txt", "data": "aGVsbG8=", "mime-type": "text/plain"}
                      ]
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void addComment_withEmptyAttachmentsList_omitsAttachmentsField() {
    wireMock.stubFor(
        post(urlEqualTo(TICKET_ARTICLES_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    client.addComment("7", "No files here", CommentType.PLAIN_TEXT, List.of(), config);

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKET_ARTICLES_URL))
            .withHeader("Authorization", equalTo("Token token=" + API_TOKEN))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "ticket_id": 7,
                      "body": "No files here",
                      "content_type": "text/plain",
                      "type": "note",
                      "internal": false
                    }
                    """,
                    true,
                    false)));
    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKET_ARTICLES_URL))
            .withRequestBody(notMatching(".*attachments.*")));
  }

  @Test
  void createTicket_withAttachments_addFilesOnCreateArticle() {
    wireMock.stubFor(
        post(urlEqualTo(TICKETS_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":42,\"number\":\"22042\"}")));

    var attachment =
        new Attachment("requestor.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
    var request =
        new CreateTicketRequest(
            "[NEGO-1] Access Request", "IN_PROGRESS", "SUBMITTED", "Body", List.of(attachment));

    client.createTicket(request, config);

    wireMock.verify(
        postRequestedFor(urlEqualTo(TICKETS_URL))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "article": {
                        "attachments": [
                          {"filename": "requestor.txt", "data": "aGVsbG8=", "mime-type": "text/plain"}
                        ]
                      }
                    }
                    """,
                    false,
                    true)));
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
                      "content_type": "text/plain",
                      "type": "note",
                      "internal": true
                    }
                    """,
                    true,
                    false)));
  }

  @Test
  void linkRelatedTickets_threeTickets_callsUpperTrianglePairs() {
    var ticketIds = List.of("1", "2", "3");
    wireMock.stubFor(
        post(urlEqualTo(LINKS_ADD_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));
    wireMock.stubFor(
        get(urlEqualTo(GET_TICKET_URL.replace("{id}", "1")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":1,\"number\":\"10001\"}")));
    wireMock.stubFor(
        get(urlEqualTo(GET_TICKET_URL.replace("{id}", "2")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":2,\"number\":\"10002\"}")));

    client.linkRelatedTickets(ticketIds, config);

    // N*(N-1)/2 = 3 calls — one per unique pair; Zammad creates a bidirectional link per call
    wireMock.verify(3, postRequestedFor(urlEqualTo(LINKS_ADD_URL)));
    wireMock.verify(1, getRequestedFor(urlEqualTo(GET_TICKET_URL.replace("{id}", "1"))));
    wireMock.verify(1, getRequestedFor(urlEqualTo(GET_TICKET_URL.replace("{id}", "2"))));

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
    client.linkRelatedTickets(List.of("1"), config);
    wireMock.verify(0, postRequestedFor(urlEqualTo(LINKS_ADD_URL)));
  }
}
