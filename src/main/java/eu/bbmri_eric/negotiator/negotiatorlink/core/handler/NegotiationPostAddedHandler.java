package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationCache;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationPostAddedWebhookEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostTagDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NegotiationPostAddedHandler implements NegotiationEventHandler {

  private static final String EVENT_TYPE = "negotiation.post.added";

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry registry;
  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedPostRepository originatedPostRepository;
  private final PendingTicketCreationCache pendingTicketCreationCache;
  private final ObjectMapper objectMapper;

  @Override
  public String getSupportedEventType() {
    return EVENT_TYPE;
  }

  @Override
  public HandlerResult handle(JsonNode data) {
    NegotiationPostAddedWebhookEvent event = parseEvent(data);
    if (event == null) {
      return HandlerResult.empty();
    }

    List<TicketMapping> mappings = getNegotiationMappings(event.getNegotiationId());
    if (mappings.isEmpty()) {
      return HandlerResult.empty();
    }

    EntityModelPostDTO post = fetchPost(event, mappings);
    if (post == null) {
      return HandlerResult.empty();
    }

    LinkedHashMap<String, String> ticketToOrgExtId =
        resolveTargets(mappings, event.getOrganizationId());
    if (ticketToOrgExtId.isEmpty()) {
      log.debug(
          "No target tickets for post '{}' in negotiation '{}'",
          event.getPostId(),
          event.getNegotiationId());
      return HandlerResult.empty();
    }

    Optional<OriginatedPost> originatedPost =
        findConsumedOriginatedPost(sha256Hex(post.getText()), ticketToOrgExtId, event);
    String text = decoratePost(post, originatedPost.isPresent());

    originatedPost.ifPresent(
        op -> excludeOriginatingTicket(ticketToOrgExtId, op, event.getPostId()));

    List<PendingIntent> intents = fanOut(ticketToOrgExtId, text, event);
    return new HandlerResult(intents, originatedPost);
  }

  /** Removes the originating ticket from the fan-out set so the post is not echoed back to it. */
  private void excludeOriginatingTicket(
      LinkedHashMap<String, String> ticketToOrgExtId,
      OriginatedPost originatedPost,
      String postId) {
    log.debug(
        "Deduplication: excluding originating ticket '{}' from fan-out for post '{}'",
        originatedPost.getTicketId(),
        postId);
    ticketToOrgExtId.remove(originatedPost.getTicketId());
  }

  /** Parses and validates the webhook event, or returns {@code null} if it is incomplete. */
  private NegotiationPostAddedWebhookEvent parseEvent(JsonNode data) {
    NegotiationPostAddedWebhookEvent event =
        objectMapper.convertValue(data, NegotiationPostAddedWebhookEvent.class);

    String negotiationId = event.getNegotiationId();
    String postId = event.getPostId();

    if (negotiationId == null || postId == null) {
      log.warn("Incomplete post added event — skipping");
      return null;
    }
    return event;
  }

  private List<TicketMapping> getNegotiationMappings(String negotiationId) {
    if (pendingTicketCreationCache.isTracked(negotiationId)) {
      throw new PendingTicketCreationException(negotiationId);
    }
    List<TicketMapping> mappings = ticketMappingRepository.findByIdNegotiationId(negotiationId);
    if (mappings.isEmpty()) {
      log.debug("No ticket mappings for negotiation '{}' — nothing to fan out", negotiationId);
      return List.of();
    }
    return mappings;
  }

  private EntityModelPostDTO fetchPost(
      NegotiationPostAddedWebhookEvent event, List<TicketMapping> mappings) {
    OrganizationConfig fetchConfig = resolveFetchConfig(mappings, event.getOrganizationId());
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
    return post;
  }

  private String decoratePost(EntityModelPostDTO post, boolean isRelayed) {
    Set<String> roleNames =
        post.getTags() != null
            ? post.getTags().stream().map(PostTagDTO::getName).collect(Collectors.toSet())
            : Collections.emptySet();

    if (isRelayed) {
      return PostUtil.insertRoleUnderRelayedHeader(post.getText(), roleNames);
    }

    UserResponseModel createdBy = post.getCreatedBy();
    Sender sender =
        createdBy != null ? new Sender(createdBy.getName(), createdBy.getEmail()) : null;
    return PostUtil.signPostWithRoles(
        post.getText(), sender, roleNames, post.getType() == EntityModelPostDTO.TypeEnum.PUBLIC);
  }

  private OrganizationConfig resolveFetchConfig(List<TicketMapping> mappings, Long organizationId) {
    if (organizationId != null) {
      String orgIdStr = String.valueOf(organizationId);
      return mappings.stream()
          .filter(mapping -> orgIdStr.equals(mapping.getOrganizationId()))
          .findFirst()
          .flatMap(mapping -> registry.findByExternalId(mapping.getOrganizationExtId()))
          .orElse(null);
    }
    // PUBLIC post: any opted-in org involved in this negotiation can fetch the post text.
    return mappings.stream()
        .findFirst()
        .flatMap(mapping -> registry.findByExternalId(mapping.getOrganizationExtId()))
        .orElse(null);
  }

  /**
   * Finds an originated post record from ticket matching the hash and org so the originating ticket
   * can be excluded from fan-out. Returns the record to be consumed, or {@code Optional.empty()}.
   */
  private Optional<OriginatedPost> findConsumedOriginatedPost(
      String textHash,
      LinkedHashMap<String, String> ticketToOrgExtId,
      NegotiationPostAddedWebhookEvent event) {
    if (event.getOrganizationId() == null) {
      return Optional.empty();
    }
    String[] ticketIds = ticketToOrgExtId.keySet().toArray(String[]::new);
    String[] orgExtIds = ticketToOrgExtId.values().toArray(String[]::new);
    return originatedPostRepository.findFirstByTextHashAndValidTicketOrgPairs(
        textHash, ticketIds, orgExtIds);
  }

  /** Produces one ADD_COMMENT intent per target ticket. */
  private List<PendingIntent> fanOut(
      LinkedHashMap<String, String> ticketToOrgExtId,
      String postText,
      NegotiationPostAddedWebhookEvent event) {
    List<PendingIntent> intents = new ArrayList<>();
    ticketToOrgExtId.forEach(
        (ticketId, orgExtId) -> {
          intents.add(addCommentIntent(ticketId, orgExtId, postText, event));
        });
    return intents;
  }

  private PendingIntent addCommentIntent(
      String ticketId, String orgExtId, String postText, NegotiationPostAddedWebhookEvent event) {
    String payload = serialize(new AddCommentPayload(postText, CommentType.PLAIN_TEXT));
    return new PendingIntent(
        OutboxIntentType.ADD_COMMENT,
        TargetKind.TICKET,
        ticketId,
        event.getNegotiationId(),
        orgExtId,
        payload);
  }

  private LinkedHashMap<String, String> resolveTargets(
      List<TicketMapping> allMappings, Long organizationId) {
    LinkedHashMap<String, String> ticketToOrgExtId = new LinkedHashMap<>();

    for (TicketMapping mapping : allMappings) {
      String ticketId = mapping.getId().getHelpdeskTicketId();
      String orgExtId = mapping.getOrganizationExtId();
      String mappingOrgId = mapping.getOrganizationId();

      if (organizationId == null) {
        ticketToOrgExtId.putIfAbsent(ticketId, orgExtId);
      } else if (String.valueOf(organizationId).equals(mappingOrgId)) {
        // PRIVATE: only tickets belonging to the post's organization
        ticketToOrgExtId.putIfAbsent(ticketId, orgExtId);
      }
    }

    return ticketToOrgExtId;
  }
}
