package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.ApiClient;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.AttachmentsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.CommentsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.NegotiationsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.UsersApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.AttachmentMetadataDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.util.List;
import java.util.Map;
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
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";

  private MockRestServiceServer server;
  private NegotiatorClient client;
  private OrganizationConfig config;

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
    var perOrgClient =
        new NegotiatorClient.PerOrgClient(
            new NegotiationsApi(apiClient),
            new CommentsApi(apiClient),
            new UsersApi(apiClient),
            new AttachmentsApi(apiClient));
    client = new NegotiatorClient(Map.of(ORG_EXT_ID, perOrgClient), BASE_URL);

    config = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
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

    EntityModelNegotiationDTO result = client.getNegotiation("neg-1", config);

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

    List<EntityModelResourceWithStatusDTO> resources = client.getResources("neg-1", config);

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

    assertThat(client.getResources("neg-1", config)).isEmpty();
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

    Set<String> events = client.getPossibleResourceEvents("neg-1", "res-1", config);

    assertThat(events).containsExactlyInAnyOrder("APPROVE", "DECLINE");
  }

  @Test
  void sendResourceEvent_sendsCorrectRequest() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/resources/res-1/lifecycle/APPROVE"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    client.sendResourceEvent("neg-1", "res-1", "APPROVE", config);

    server.verify();
  }

  @Test
  void sendBulkResourceEvent_buildsCorrectPatchBody() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/resources"))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    client.sendBulkResourceEvent("neg-1", List.of(10L, 11L), "CHECKING_AVAILABILITY", config);

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
                {"id":"post-1","text":"hello","type":"PRIVATE"}
                """,
                MediaType.APPLICATION_JSON));

    var dto = new PostCreateDTO();
    dto.setText("hello");
    dto.setType(PostCreateDTO.TypeEnum.PRIVATE);

    EntityModelPostDTO result = client.createPost("neg-1", dto, config);

    assertThat(result.getId()).isEqualTo("post-1");
  }

  @Test
  void getPost_returnsPostDto() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/posts/post-99"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(
            withSuccess(
                """
                {"id":"post-99","text":"some message","type":"PRIVATE"}
                """,
                MediaType.APPLICATION_JSON));

    EntityModelPostDTO result = client.getPost("neg-1", "post-99", config);

    assertThat(result.getId()).isEqualTo("post-99");
    assertThat(result.getText()).isEqualTo("some message");
  }

  @Test
  void whenNegotiatorReturns5xx_throwsRestClientException() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-err"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.getNegotiation("neg-err", config))
        .isInstanceOf(RestClientException.class);
  }

  @Test
  void getPosts_unwrapsHateoasEmbedded() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/posts"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"posts":[
                  {"id":"post-1","text":"hello","type":"PRIVATE"},
                  {"id":"post-2","text":"world","type":"PUBLIC"}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    List<EntityModelPostDTO> posts = client.getPosts("neg-1", config);

    assertThat(posts).hasSize(2);
    assertThat(posts.get(0).getId()).isEqualTo("post-1");
    assertThat(posts.get(1).getId()).isEqualTo("post-2");
  }

  @Test
  void getPosts_whenEmbeddedNull_returnsEmptyList() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/posts"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(client.getPosts("neg-1", config)).isEmpty();
  }

  @Test
  void getPosts_whenPostsListNull_returnsEmptyList() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/posts"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{}}
                """,
                MediaType.APPLICATION_JSON));

    assertThat(client.getPosts("neg-1", config)).isEmpty();
  }

  @Test
  void getAttachments_returnsDeserializedMetadataList() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/attachments"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(
            withSuccess(
                """
                [
                  {"id":"a-1","name":"public.txt","contentType":"text/plain","size":5},
                  {"id":"a-2","organizationId":"org-9","name":"private.txt",
                   "contentType":"text/plain","size":9}
                ]
                """,
                MediaType.APPLICATION_JSON));

    List<AttachmentMetadataDTO> attachments = client.getAttachments("neg-1", config);

    assertThat(attachments).hasSize(2);
    assertThat(attachments.get(0).getId()).isEqualTo("a-1");
    assertThat(attachments.get(0).getOrganizationId()).isNull();
    assertThat(attachments.get(1).getId()).isEqualTo("a-2");
    assertThat(attachments.get(1).getOrganizationId()).isEqualTo("org-9");
  }

  @Test
  void getAttachments_whenBodyNull_returnsEmptyList() {
    server
        .expect(requestTo(BASE_URL + "/v3/negotiations/neg-1/attachments"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess());

    assertThat(client.getAttachments("neg-1", config)).isEmpty();
  }

  @Test
  void downloadAttachment_returnsRawBytes() {
    byte[] content = "file-bytes".getBytes();
    server
        .expect(requestTo(BASE_URL + "/v3/attachments/a-1"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
        .andRespond(withSuccess(content, MediaType.APPLICATION_OCTET_STREAM));

    byte[] result = client.downloadAttachment("a-1", config);

    assertThat(result).isEqualTo(content);
  }

  @Test
  void downloadAttachment_whenNegotiatorReturns5xx_throwsRestClientException() {
    server
        .expect(requestTo(BASE_URL + "/v3/attachments/a-err"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.downloadAttachment("a-err", config))
        .isInstanceOf(RestClientException.class);
  }
}
