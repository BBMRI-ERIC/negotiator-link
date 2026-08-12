package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.CommentsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.api.NegotiationsApi;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UpdateResourcesDTO;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.client.RestClient;

public class NegotiatorClient {

  private final NegotiationsApi negotiationsApi;
  private final CommentsApi commentsApi;

  public NegotiatorClient(RestClient restClient, String baseUrl) {
    var apiClient = new eu.bbmri_eric.negotiator.negotiatorlink.generated.ApiClient(restClient);
    apiClient.setBasePath(baseUrl);
    this.negotiationsApi = new NegotiationsApi(apiClient);
    this.commentsApi = new CommentsApi(apiClient);
  }

  public NegotiatorClient(NegotiationsApi negotiationsApi, CommentsApi commentsApi) {
    this.negotiationsApi = negotiationsApi;
    this.commentsApi = commentsApi;
  }

  public EntityModelNegotiationDTO getNegotiation(String negotiationId) {
    return negotiationsApi.retrieve2(negotiationId);
  }

  public List<EntityModelResourceWithStatusDTO> getResources(String negotiationId) {
    var result = negotiationsApi.findResourcesForNegotiation(negotiationId);
    if (result == null || result.getEmbedded() == null) {
      return List.of();
    }
    return Objects.requireNonNullElse(result.getEmbedded().getResources(), List.of());
  }

  /**
   * Get possible resource events, which can be done based on resource current state
   *
   * @param negotiationId of the negotiation including the resource
   * @param resourceSourceId of the resource
   * @return the Set of possible resource transition events
   */
  public Set<String> getPossibleResourceEvents(String negotiationId, String resourceSourceId) {
    List<String> events =
        negotiationsApi.getPossibleEventsForNegotiationResource(negotiationId, resourceSourceId);
    return events == null ? Set.of() : Set.copyOf(events);
  }

  /**
   * Send event to Negotiator to update resource state.
   *
   * @param negotiationId in which the resource is involved
   * @param resourceSourceId reference the source ID of the resource and determines which resource
   *     to change
   * @param event representing the transition action
   * @param helpdeskActor gives the identity of the actor who is performing the action
   */
  public void sendResourceEvent(
      String negotiationId, String resourceSourceId, String event, String helpdeskActor) {
    negotiationsApi.sendEventForNegotiationResource(
        negotiationId, resourceSourceId, event, helpdeskActor);
  }

  /**
   * Bulk endpoint for updating resource states.
   *
   * @param negotiationId in which the resources are involved
   * @param resourceIds reference the resource ID and determines which resources to change
   * @param event representing the transition action
   * @param helpdeskActor gives the identity of the actor who is performing the action
   */
  public void sendBulkResourceEvent(
      String negotiationId, List<Long> resourceIds, String event, String helpdeskActor) {
    var dto = new UpdateResourcesDTO();
    dto.setResourceIds(resourceIds);
    dto.setState(UpdateResourcesDTO.StateEnum.fromValue(event));
    dto.setHelpdeskActor(helpdeskActor);
    negotiationsApi.updateResources(negotiationId, dto);
  }

  public EntityModelPostDTO createPost(String negotiationId, PostCreateDTO post) {
    return commentsApi.add2(negotiationId, post);
  }

  public EntityModelPostDTO getPostText(String postId) {
    return commentsApi.getById(postId);
  }
}
