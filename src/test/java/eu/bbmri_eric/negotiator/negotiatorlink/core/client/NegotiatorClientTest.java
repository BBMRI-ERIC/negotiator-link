package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import eu.bbmri_eric.negotiator.negotiatorlink.generated.ApiClient;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.CommentsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.NegotiationsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class NegotiatorClientTest {

  private static final String BASE_URL = "http://negotiator.test";
  private static final String BEARER_TOKEN = "test-bearer-token";

  private MockRestServiceServer server;
  private NegotiatorClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient =
        builder
            .requestInterceptor(
                (req, body, execution) -> {
                  req.getHeaders().setBearerAuth(BEARER_TOKEN);
                  return execution.execute(req, body);
                })
            .build();

    ApiClient apiClient = new ApiClient(restClient);
    apiClient.setBasePath(BASE_URL);
    client = new NegotiatorClient(new NegotiationsApi(apiClient), new CommentsApi(apiClient));
  }

  @Test
  void getNegotiation_returnsDeserializedDto() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(
            withSuccess(
                """
                {"id":"neg-1","displayId":"NEGO-1","status":"IN_PROGRESS"}
                """,
                MediaType.APPLICATION_JSON));

    EntityModelNegotiationDTO result = client.getNegotiation("neg-1");

    assertThat(result.getId()).isEqualTo("neg-1");
    assertThat(result.getDisplayId()).isEqualTo("NEGO-1");
    assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void getResources_unwrapsHateoasEmbedded() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/resources"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"resources":[
                  {"id":42,"sourceId":"src-1","name":"Resource A",
                   "organization":{"id":1,"externalId":"bbmri:DE:MHH","name":"MHH",
                     "description":"desc","externalId":"bbmri:DE:MHH"},
                   "currentState":"SUBMITTED"}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    List<EntityModelResourceWithStatusDTO> resources = client.getResources("neg-1");

    assertThat(resources).hasSize(1);
    assertThat(resources.getFirst().getSourceId()).isEqualTo("src-1");
    assertThat(resources.getFirst().getId()).isEqualTo(42L);
  }

  @Test
  void getResources_whenEmbeddedNull_returnsEmptyList() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/resources"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(client.getResources("neg-1")).isEmpty();
  }

  @Test
  void getPossibleResourceEvents_returnsSetOfStrings() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/resources/res-1/lifecycle"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                ["APPROVE","DECLINE"]
                """,
                MediaType.APPLICATION_JSON));

    Set<String> events = client.getPossibleResourceEvents("neg-1", "res-1");

    assertThat(events).containsExactlyInAnyOrder("APPROVE", "DECLINE");
  }

  @Test
  void sendResourceEvent_sendsCorrectRequestWithHelpdeskActorHeader() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/resources/res-1/lifecycle/APPROVE"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andExpect(header("X-Helpdesk-Actor", "john.doe@example.com"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    client.sendResourceEvent("neg-1", "res-1", "APPROVE", "john.doe@example.com");

    server.verify();
  }

  @Test
  void sendBulkResourceEvent_buildsCorrectPatchBody() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/resources"))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    client.sendBulkResourceEvent(
        "neg-1", List.of(10L, 11L), "CHECKING_AVAILABILITY", "jane@example.com");

    server.verify();
  }

  @Test
  void createPost_sendsPostBodyAndReturnsDto() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/posts"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(
            withSuccess(
                """
                {"id":"post-1","text":"hello","type":"PRIVATE","helpdeskActor":"jane@example.com"}
                """,
                MediaType.APPLICATION_JSON));

    var dto = new PostCreateDTO();
    dto.setText("hello");
    dto.setType(PostCreateDTO.TypeEnum.PRIVATE);
    dto.setHelpdeskActor("jane@example.com");

    EntityModelPostDTO result = client.createPost("neg-1", dto);

    assertThat(result.getId()).isEqualTo("post-1");
    assertThat(result.getHelpdeskActor()).isEqualTo("jane@example.com");
  }

  @Test
  void getPostText_returnsPostDto() {
    server
        .expect(requestTo(BASE_URL + "/v3/posts/post-99"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(
            withSuccess(
                """
                {"id":"post-99","text":"some message","type":"PRIVATE"}
                """,
                MediaType.APPLICATION_JSON));

    EntityModelPostDTO result = client.getPostText("post-99");

    assertThat(result.getId()).isEqualTo("post-99");
    assertThat(result.getText()).isEqualTo("some message");
  }

  @Test
  void whenNegotiatorReturns5xx_throwsRestClientException() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-err"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.getNegotiation("neg-err"))
        .isInstanceOf(RestClientException.class);
  }
}
