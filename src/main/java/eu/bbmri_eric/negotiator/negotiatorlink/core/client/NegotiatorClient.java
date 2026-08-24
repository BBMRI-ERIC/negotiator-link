package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorCredentials;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.ApiClient;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.CommentsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.NegotiationsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.UsersApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelUserInfoModel;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UpdateResourcesDTO;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

@Slf4j
public class NegotiatorClient {

  private final String baseUrl;
  private final String issuerUri;
  private final RestClient.Builder restClientBuilder;
  private final Map<String, PerOrgClient> clientCache = new ConcurrentHashMap<>();

  public record PerOrgClient(NegotiationsApi negotiations, CommentsApi comments, UsersApi users) {}

  public NegotiatorClient(RestClient.Builder restClientBuilder, String baseUrl, String issuerUri) {
    this.restClientBuilder = restClientBuilder;
    this.baseUrl = baseUrl;
    this.issuerUri = issuerUri;
  }

  /** Test constructor — injects pre-built per-org clients directly. */
  public NegotiatorClient(Map<String, PerOrgClient> prebuiltClients, String baseUrl) {
    this.restClientBuilder = null;
    this.baseUrl = baseUrl;
    this.issuerUri = null;
    this.clientCache.putAll(prebuiltClients);
  }

  /**
   * Get a negotiation by its ID.
   *
   * @param negotiationId the ID of the negotiation to retrieve
   * @param config organization configuration to use for this request
   * @return the response EntityModelNegotiationDTO object
   */
  public EntityModelNegotiationDTO getNegotiation(String negotiationId, OrganizationConfig config) {
    return getOrCreateClient(config).negotiations().retrieve2(negotiationId);
  }

  /**
   * Get the resources associated with a negotiation.
   *
   * @param negotiationId the ID of the negotiation
   * @param config organization configuration to use for this request
   * @return a list of resources associated with the negotiation
   */
  public List<EntityModelResourceWithStatusDTO> getResources(
      String negotiationId, OrganizationConfig config) {
    var result =
        getOrCreateClient(config).negotiations().findResourcesForNegotiation(negotiationId);
    if (result == null || result.getEmbedded() == null) {
      return List.of();
    }
    return Objects.requireNonNullElse(result.getEmbedded().getResources(), List.of());
  }

  /**
   * Get the possible transition events for a resource in a negotiation.
   *
   * @param negotiationId in which the resource is involved
   * @param resourceSourceId reference the source ID of the resource and determines which resource
   *     to change
   * @param config organization configuration to use for this request
   * @return a set of possible events for the resource
   */
  public Set<String> getPossibleResourceEvents(
      String negotiationId, String resourceSourceId, OrganizationConfig config) {
    List<String> events =
        getOrCreateClient(config)
            .negotiations()
            .getPossibleEventsForNegotiationResource(negotiationId, resourceSourceId);
    return events == null ? Set.of() : Set.copyOf(events);
  }

  /**
   * Send event to Negotiator to update resource state.
   *
   * @param negotiationId in which the resource is involved
   * @param resourceSourceId reference the source ID of the resource and determines which resource
   *     to change
   * @param event representing the transition action
   * @param config organization configuration to use for this request
   */
  public void sendResourceEvent(
      String negotiationId, String resourceSourceId, String event, OrganizationConfig config) {
    getOrCreateClient(config)
        .negotiations()
        .sendEventForNegotiationResource(negotiationId, resourceSourceId, event);
  }

  /**
   * Bulk endpoint for updating resource states.
   *
   * @param negotiationId in which the resources are involved
   * @param resourceIds reference the resource ID and determines which resources to change
   * @param targetState the state to which the resources should be transitioned
   * @param config organization configuration to use for this request
   */
  public void sendBulkResourceEvent(
      String negotiationId, List<Long> resourceIds, String targetState, OrganizationConfig config) {
    var dto = new UpdateResourcesDTO();
    dto.setResourceIds(resourceIds);
    dto.setState(UpdateResourcesDTO.StateEnum.fromValue(targetState));
    getOrCreateClient(config).negotiations().updateResources(negotiationId, dto);
  }

  /**
   * Create a new post for the specified negotiation.
   *
   * @param negotiationId the ID of the negotiation to which the post refers
   * @param post the Post DTO containing request information - text: the content of the post,
   *     organizationId: the ID of the organization that is creating this post, used for PRIVATE
   *     posts, type: the type of the post (e.g., PRIVATE, PUBLIC)
   * @param config organization configuration to use for this request
   * @return the response PostDTO object
   */
  public EntityModelPostDTO createPost(
      String negotiationId, PostCreateDTO post, OrganizationConfig config) {
    return getOrCreateClient(config).comments().add2(negotiationId, post);
  }

  /**
   * Get a post by its ID.
   *
   * @param negotiationId the ID of the negotiation to which the post belongs
   * @param postId the ID of the post to retrieve
   * @param config organization configuration to use for this request
   * @return the response PostDTO object
   */
  public EntityModelPostDTO getPost(
      String negotiationId, String postId, OrganizationConfig config) {
    return getOrCreateClient(config).comments().getById(negotiationId, postId);
  }

  /**
   * Get all posts for a negotiation.
   *
   * @param negotiationId the ID of the negotiation
   * @param config organization configuration to use for this request
   * @return list of posts, empty if none
   */
  public List<EntityModelPostDTO> getPosts(String negotiationId, OrganizationConfig config) {
    var result = getOrCreateClient(config).comments().getAllMessagesByNegotiation(negotiationId);
    if (result == null || result.getEmbedded() == null) {
      return List.of();
    }
    return Objects.requireNonNullElse(result.getEmbedded().getPosts(), List.of());
  }

  /**
   * Get the user info for the current client.
   *
   * @param config organization configuration to use for this request
   * @return the response UserInfoModel object
   */
  public EntityModelUserInfoModel getUserInfo(OrganizationConfig config) {
    return getOrCreateClient(config).users().userInfo();
  }

  private PerOrgClient getOrCreateClient(OrganizationConfig config) {
    return clientCache.computeIfAbsent(
        config.organizationExternalId(), id -> buildClient(config.negotiatorCredentials()));
  }

  private PerOrgClient buildClient(NegotiatorCredentials creds) {
    var manager = createAuthorizedClientManager(creds);
    var restClient = createAuthenticatedRestClient(manager, creds);
    var apiClient = createApiClient(restClient);
    return new PerOrgClient(
        new NegotiationsApi(apiClient), new CommentsApi(apiClient), new UsersApi(apiClient));
  }

  private AuthorizedClientServiceOAuth2AuthorizedClientManager createAuthorizedClientManager(
      NegotiatorCredentials creds) {
    var registration =
        ClientRegistrations.fromIssuerLocation(issuerUri)
            .registrationId("negotiator")
            .clientId(creds.clientId())
            .clientSecret(creds.clientSecret())
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("negotiator_helpdesk")
            .build();
    var registrationRepo = new InMemoryClientRegistrationRepository(registration);
    var clientService = new InMemoryOAuth2AuthorizedClientService(registrationRepo);
    var manager =
        new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrationRepo, clientService);
    manager.setAuthorizedClientProvider(
        OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    return manager;
  }

  private RestClient createAuthenticatedRestClient(
      AuthorizedClientServiceOAuth2AuthorizedClientManager manager, NegotiatorCredentials creds) {
    return restClientBuilder
        .clone()
        .requestInterceptor(
            (request, body, execution) -> {
              request.getHeaders().setBearerAuth(resolveAccessToken(manager, creds));
              return execution.execute(request, body);
            })
        .build();
  }

  private String resolveAccessToken(
      AuthorizedClientServiceOAuth2AuthorizedClientManager manager, NegotiatorCredentials creds) {
    var authorizeRequest =
        OAuth2AuthorizeRequest.withClientRegistrationId("negotiator")
            .principal("negotiator-link")
            .build();
    var authorizedClient = manager.authorize(authorizeRequest);
    if (authorizedClient == null) {
      log.error("Failed to obtain OAuth2 access token for client '{}'", creds.clientId());
      throw new IllegalStateException(
          "Could not obtain OAuth2 access token for client '%s'".formatted(creds.clientId()));
    }
    return authorizedClient.getAccessToken().getTokenValue();
  }

  private ApiClient createApiClient(RestClient restClient) {
    var apiClient = new ApiClient(restClient);
    apiClient.setBasePath(baseUrl);
    return apiClient;
  }
}
