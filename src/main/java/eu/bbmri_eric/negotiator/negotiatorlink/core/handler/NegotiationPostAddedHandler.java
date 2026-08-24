package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.signPost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationPostAddedWebhookEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NegotiationPostAddedHandler implements NegotiationEventHandler {

  private static final String EVENT_TYPE = "negotiation.post.added";

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry registry;
  private final Map<HelpDeskType, HelpDeskAdapter> adapters;
  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedPostRepository originatedPostRepository;
  private final ObjectMapper objectMapper;

  public NegotiationPostAddedHandler(
      NegotiatorClient negotiatorClient,
      OrganizationConfigRegistry registry,
      Map<HelpDeskType, HelpDeskAdapter> adapters,
      TicketMappingRepository ticketMappingRepository,
      OriginatedPostRepository originatedPostRepository,
      ObjectMapper objectMapper) {
    this.negotiatorClient = negotiatorClient;
    this.registry = registry;
    this.adapters = adapters;
    this.ticketMappingRepository = ticketMappingRepository;
    this.originatedPostRepository = originatedPostRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public void handle(JsonNode data) {
    NegotiationPostAddedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationPostAddedWebhookEvent.class);

    PostContent content = fetchPostContent(event);
    if (content == null) {
      return;
    }

    Map<String, String> ticketToOrgExtId = resolveTargetTickets(event, content.isPublic());
    if (ticketToOrgExtId.isEmpty()) {
      log.debug(
          "No target tickets for post '{}' in negotiation '{}'",
          event.getPostId(),
          event.getNegotiationId());
      return;
    }

    String excludedTicketId =
        resolveDeduplicatedTicketId(
            content.textHash(), event.getOrganizationId(), event.getPostId(), content.isPublic());

    fanOut(ticketToOrgExtId, content.text(), excludedTicketId, event.getPostId());
  }

  /** Loads the post and returns its content, or {@code null} if it cannot be forwarded. */
  private PostContent fetchPostContent(NegotiationPostAddedWebhookEvent event) {
    OrganizationConfig fetchConfig =
        resolveFetchConfig(event.getNegotiationId(), event.getOrganizationId());
    String postId = event.getPostId();
    if (fetchConfig == null) {
      log.warn("No usable org config to fetch post '{}' — skipping", postId);
      return null;
    }
    EntityModelPostDTO post =
        negotiatorClient.getPost(event.getNegotiationId(), postId, fetchConfig);
    if (post == null || post.getText() == null) {
      log.warn("Post '{}' not found or has no text — skipping", postId);
      return null;
    }
    boolean isPublic = post.getType() == EntityModelPostDTO.TypeEnum.PUBLIC;
    if (!isPublic && event.getOrganizationId() == null) {
      log.warn(
          "Post '{}' is PRIVATE but no organization ID was provided in the event — skipping",
          postId);
      return null;
    }

    UserResponseModel createdBy = post.getCreatedBy();
    Sender sender =
        createdBy != null ? new Sender(createdBy.getName(), createdBy.getEmail()) : null;
    String postText = signPost(post.getText(), sender);
    return new PostContent(postText, isPublic, sha256Hex(post.getText()));
  }

  private OrganizationConfig resolveFetchConfig(String negotiationId, Long organizationId) {
    if (organizationId != null) {
      String orgIdStr = String.valueOf(organizationId);
      return ticketMappingRepository.findAll().stream()
          .filter(mapping -> orgIdStr.equals(mapping.getOrganizationId()))
          .findFirst()
          .flatMap(mapping -> registry.findByExternalId(mapping.getOrganizationExtId()))
          .orElse(null);
    }
    // PUBLIC post: any opted-in org involved in this negotiation can fetch the post text.
    return ticketMappingRepository.findByIdNegotiationId(negotiationId).stream()
        .findFirst()
        .flatMap(mapping -> registry.findByExternalId(mapping.getOrganizationExtId()))
        .orElse(null);
  }

  /** Resolves the distinct target tickets for the post based on its visibility. */
  private Map<String, String> resolveTargetTickets(
      NegotiationPostAddedWebhookEvent event, boolean isPublic) {
    List<TicketMapping> allMappings =
        ticketMappingRepository.findByIdNegotiationId(event.getNegotiationId());
    if (allMappings.isEmpty()) {
      log.debug(
          "No ticket mappings for negotiation '{}' — nothing to fan out", event.getNegotiationId());
      return Map.of();
    }
    return resolveTargets(allMappings, isPublic, event.getOrganizationId());
  }

  /**
   * Deduplication: finds and consumes one originated-post record matching the hash and org, so the
   * originating ticket is excluded from fan-out. Returns the excluded ticket id, or {@code null}.
   */
  private String resolveDeduplicatedTicketId(
      String textHash, Long organizationId, String postId, boolean isPublic) {
    if (isPublic) {
      // help desks does not produce public messages -> no deduplication needed
      return null;
    }
    String orgIdStr = String.valueOf(organizationId);
    return originatedPostRepository
        .findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(textHash, orgIdStr)
        .map(
            originated -> {
              log.debug(
                  "Deduplication: excluding originating ticket '{}' from fan-out for post '{}'",
                  originated.getTicketId(),
                  postId);
              return originated.getTicketId();
            })
        .orElse(null);
  }

  /** Forwards the post text to every target ticket, skipping the deduplicated one. */
  private void fanOut(
      Map<String, String> ticketToOrgExtId,
      String postText,
      String excludedTicketId,
      String postId) {
    ticketToOrgExtId.forEach(
        (ticketId, orgExtId) -> {
          if (ticketId.equals(excludedTicketId)) {
            return;
          }
          forwardToTicket(ticketId, orgExtId, postText, postId);
        });
  }

  private void forwardToTicket(String ticketId, String orgExtId, String postText, String postId) {
    OrganizationConfig config = registry.findByExternalId(orgExtId).orElseThrow();
    HelpDeskAdapter adapter = adapters.get(config.type());
    if (adapter == null) {
      log.error("No adapter for type '{}' (org '{}')", config.type(), orgExtId);
      return;
    }

    try {
      adapter.addComment(ticketId, postText, config);
      log.info("Forwarded post '{}' to ticket '{}'", postId, ticketId);
    } catch (Exception e) {
      log.error("Failed to add comment to ticket '{}' for post '{}'", ticketId, postId, e);
    }
  }

  private Map<String, String> resolveTargets(
      List<TicketMapping> allMappings, boolean isPublic, Long organizationId) {
    Map<String, String> ticketToOrgExtId = new LinkedHashMap<>();

    for (TicketMapping mapping : allMappings) {
      String ticketId = mapping.getId().getHelpdeskTicketId();
      String orgExtId = mapping.getOrganizationExtId();
      String mappingOrgId = mapping.getOrganizationId();

      if (isPublic) {
        ticketToOrgExtId.putIfAbsent(ticketId, orgExtId);
      } else if (organizationId != null && String.valueOf(organizationId).equals(mappingOrgId)) {
        // PRIVATE: only tickets belonging to the post's organization
        ticketToOrgExtId.putIfAbsent(ticketId, orgExtId);
      }
    }

    return ticketToOrgExtId;
  }

  /** Bundles the fetched post's text, visibility, and content hash. */
  private record PostContent(String text, boolean isPublic, String textHash) {}
}
