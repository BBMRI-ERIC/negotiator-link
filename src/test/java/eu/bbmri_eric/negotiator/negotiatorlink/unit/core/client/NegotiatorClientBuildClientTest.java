package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.client;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.io.IOException;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class NegotiatorClientBuildClientTest {

  private static final String ORG_EXT_ID = "bbmri:DE:MHH";

  private MockWebServer negotiatorServer; // serves the Negotiator API
  private MockWebServer authServer; // serves OIDC discovery + token endpoint
  private NegotiatorClient client;
  private OrganizationConfig config;

  @BeforeEach
  void setUp() throws IOException {
    authServer = new MockWebServer();
    authServer.start();
    negotiatorServer = new MockWebServer();
    negotiatorServer.start();

    String issuerUri = authServer.url("/").toString().replaceAll("/$", "");
    String baseUrl = negotiatorServer.url("/").toString().replaceAll("/$", "");

    // Route requests based on path so buildClient's discovery + token calls succeed
    authServer.setDispatcher(oidcDispatcher(issuerUri));

    client = new NegotiatorClient(RestClient.builder(), baseUrl, issuerUri);
    config = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
  }

  @AfterEach
  void tearDown() throws IOException {
    authServer.shutdown();
    negotiatorServer.shutdown();
  }

  @Test
  void buildClient_performsDiscoveryTokenExchangeAndAttachesBearer() throws InterruptedException {
    negotiatorServer.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                                {"id":"neg-1","displayId":"NEGO-1","status":"IN_PROGRESS"}
                                """));

    EntityModelNegotiationDTO result = client.getNegotiation("neg-1", config);

    assertThat(result.getId()).isEqualTo("neg-1");

    RecordedRequest apiRequest = negotiatorServer.takeRequest();
    assertThat(apiRequest.getPath()).isEqualTo("/v3/negotiations/neg-1");
    assertThat(apiRequest.getHeader("Authorization")).isEqualTo("Bearer issued-token-123");
  }

  @Test
  void buildClient_cachesClientPerOrg_soDiscoveryHappensOnce() {
    negotiatorServer.enqueue(jsonNegotiation());
    negotiatorServer.enqueue(jsonNegotiation());

    client.getNegotiation("neg-1", config);
    int authRequestsAfterFirst = authServer.getRequestCount();

    client.getNegotiation("neg-2", config); // same org -> cached PerOrgClient

    assertThat(authServer.getRequestCount()).isEqualTo(authRequestsAfterFirst);
  }

  private Dispatcher oidcDispatcher(String issuerUri) {
    return new Dispatcher() {
      @NotNull
      @Override
      public MockResponse dispatch(@NotNull RecordedRequest request) {
        String path = request.getPath();
        if (path != null && path.contains("/.well-known/openid-configuration")) {
          return discoveryResponse(issuerUri);
        }
        if (path != null && path.contains("/token")) {
          return tokenResponse();
        }
        return new MockResponse().setResponseCode(404);
      }
    };
  }

  private static MockResponse discoveryResponse(String issuerUri) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
                        {
                          "issuer": "%s",
                          "token_endpoint": "%s/token",
                          "authorization_endpoint": "%s/authorize",
                          "jwks_uri": "%s/jwks",
                          "response_types_supported": ["code"],
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """
                .formatted(issuerUri, issuerUri, issuerUri, issuerUri));
  }

  private static MockResponse tokenResponse() {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
                        {
                          "access_token": "issued-token-123",
                          "token_type": "Bearer",
                          "expires_in": 3600
                        }
                        """);
  }

  private static MockResponse jsonNegotiation() {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"id\":\"neg-x\",\"displayId\":\"NEGO-X\",\"status\":\"IN_PROGRESS\"}");
  }
}
