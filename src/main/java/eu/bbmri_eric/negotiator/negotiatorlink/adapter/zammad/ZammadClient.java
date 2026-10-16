package eu.bbmri_eric.negotiator.negotiatorlink.adapter.zammad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Outbound Zammad integration: creates and updates tickets via the Zammad REST API. */
@Slf4j
@Component
public class ZammadClient implements HelpdeskClient {

  private static final String AUTH_HEADER = "Authorization";
  private static final String TICKET_ARTICLES_PATH = "/api/v1/ticket_articles";
  private static final String TICKETS_PATH = "/api/v1/tickets";
  private static final String TICKET_BY_ID_PATH = TICKETS_PATH + "/{id}";
  private static final String LINKS_ADD_PATH = "/api/v1/links/add";

  private static final String ARTICLE_TYPE_NOTE = "note";
  private static final String STATE_FIELD = "state";

  private final RestClient.Builder restClientBuilder;
  private final Map<String, RestClient> clientCache = new ConcurrentHashMap<>();

  public ZammadClient(RestClient.Builder restClientBuilder) {
    this.restClientBuilder = restClientBuilder;
  }

  @Override
  public HelpdeskType getType() {
    return HelpdeskType.ZAMMAD;
  }

  @Override
  public String createTicket(CreateTicketRequest request, OrganizationConfig config) {
    Map<String, Object> body = buildCreateTicketBody(request, config);

    TicketResponse response =
        buildClient(config)
            .post()
            .uri(TICKETS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(TicketResponse.class);

    Long ticketId = parseCreatedTicket(response);
    log.info("Created Zammad ticket id={} title='{}'", ticketId, request.title());
    return String.valueOf(ticketId);
  }

  @Override
  public void updateNegotiationState(
      String ticketId, String negotiationState, OrganizationConfig config) {
    putTicket(ticketId, Map.of(ZammadFields.NEGOTIATION_STATE, negotiationState), config);
  }

  @Override
  public void updateResourceState(
      String ticketId, String resourceState, OrganizationConfig config) {
    Map<String, Object> body = new HashMap<>();
    body.put(ZammadFields.RESOURCE_STATE, resourceState);
    body.put(ZammadFields.TRANSITION_EVENT, null);
    putTicket(ticketId, body, config);
  }

  @Override
  public void updateTicketState(String ticketId, String ticketState, OrganizationConfig config) {
    putTicket(ticketId, Map.of(STATE_FIELD, ticketState), config);
  }

  @Override
  public void addComment(
      String ticketId, String commentText, CommentType commentType, OrganizationConfig config) {
    postArticle(ticketId, commentText, commentType, false, config);
  }

  @Override
  public void addComment(
      String ticketId,
      String commentText,
      CommentType commentType,
      List<Attachment> attachments,
      OrganizationConfig config) {
    post(
        buildClient(config),
        TICKET_ARTICLES_PATH,
        buildArticleBody(ticketId, commentText, commentType, false, attachments));
  }

  @Override
  public void addSystemComment(
      String ticketId, String systemCommentText, OrganizationConfig config) {
    postArticle(ticketId, systemCommentText, CommentType.PLAIN_TEXT, true, config);
  }

  @Override
  public void linkRelatedTickets(List<String> ticketIds, OrganizationConfig config) {
    if (ticketIds.size() < 2) {
      return;
    }
    RestClient client = buildClient(config);
    for (int i = 0; i < ticketIds.size() - 1; i++) {
      String sourceTicketNumber = getTicketNumber(client, ticketIds.get(i));
      for (int j = i + 1; j < ticketIds.size(); j++) {
        post(client, LINKS_ADD_PATH, buildLinkBody(sourceTicketNumber, ticketIds.get(j)));
      }
    }
  }

  private String getTicketNumber(RestClient client, String ticketId) {
    TicketResponse response =
        client.get().uri(TICKET_BY_ID_PATH, ticketId).retrieve().body(TicketResponse.class);
    if (response == null || response.number() == null) {
      throw new IllegalStateException(
          "Zammad getTicket returned no number for ticketId=" + ticketId);
    }
    return response.number();
  }

  private Map<String, Object> buildCreateTicketBody(
      CreateTicketRequest request, OrganizationConfig config) {
    Map<String, Object> article = new HashMap<>();
    article.put("subject", request.title());
    article.put("body", request.ticketBody());
    article.put("content_type", "text/html");
    article.put("type", ARTICLE_TYPE_NOTE);
    article.put("internal", false);
    if (!request.attachments().isEmpty()) {
      article.put("attachments", attachmentsPayload(request.attachments()));
    }

    Map<String, Object> body = new HashMap<>();
    body.put("title", request.title());
    body.put("group", config.platformConfig().get("group"));
    body.put("customer", config.platformConfig().get("botEmail"));
    body.put(ZammadFields.NEGOTIATION_STATE, request.negotiationState());
    body.put(ZammadFields.RESOURCE_STATE, request.resourceState());
    body.put(ZammadFields.TRANSITION_EVENT, null);
    body.put("article", article);
    return body;
  }

  private Map<String, Object> buildArticleBody(
      String ticketId, String text, CommentType commentType, boolean internal) {
    Map<String, Object> body = new HashMap<>();
    body.put("ticket_id", Long.parseLong(ticketId));
    body.put("body", text);
    body.put("content_type", commentType == CommentType.HTML ? "text/html" : "text/plain");
    body.put("type", ARTICLE_TYPE_NOTE);
    body.put("internal", internal);
    return body;
  }

  private Map<String, Object> buildArticleBody(
      String ticketId,
      String text,
      CommentType commentType,
      boolean internal,
      List<Attachment> attachments) {
    Map<String, Object> body = buildArticleBody(ticketId, text, commentType, internal);
    if (attachments != null && !attachments.isEmpty()) {
      body.put("attachments", attachmentsPayload(attachments));
    }
    return body;
  }

  private Map<String, Object> buildLinkBody(String sourceTicketNumber, String targetTicketId) {
    Map<String, Object> body = new HashMap<>();
    body.put("link_type", "normal");
    body.put("link_object_source", "Ticket");
    body.put("link_object_source_number", sourceTicketNumber);
    body.put("link_object_target", "Ticket");
    body.put("link_object_target_value", Long.parseLong(targetTicketId));
    return body;
  }

  private List<Map<String, Object>> attachmentsPayload(List<Attachment> attachments) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (Attachment attachment : attachments) {
      payload.add(toZammadAttachment(attachment));
    }
    return payload;
  }

  private Map<String, Object> toZammadAttachment(Attachment attachment) {
    Map<String, Object> map = new HashMap<>();
    map.put("filename", attachment.filename());
    map.put("data", Base64.getEncoder().encodeToString(attachment.content()));
    map.put("mime-type", attachment.contentType());
    return map;
  }

  private void postArticle(
      String ticketId,
      String text,
      CommentType commentType,
      boolean internal,
      OrganizationConfig config) {
    post(
        buildClient(config),
        TICKET_ARTICLES_PATH,
        buildArticleBody(ticketId, text, commentType, internal));
  }

  private void putTicket(String ticketId, Map<String, Object> body, OrganizationConfig config) {
    buildClient(config)
        .put()
        .uri(TICKET_BY_ID_PATH, ticketId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  private void post(RestClient client, String path, Map<String, Object> body) {
    client
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  private RestClient buildClient(OrganizationConfig config) {
    // Cached per organizationExternalId since a given org's apiToken/baseUrl are immutable at
    // runtime (env-var config), so a cached client never goes stale. Reusing the client reuses its
    // underlying connection pool instead of allocating a new one on every API call.
    return clientCache.computeIfAbsent(
        config.organizationExternalId(),
        id ->
            restClientBuilder
                .clone()
                .baseUrl(config.baseUrl())
                .defaultHeader(AUTH_HEADER, "Token token=" + config.credentials().get("apiToken"))
                .build());
  }

  private Long parseCreatedTicket(TicketResponse response) {
    if (response == null || response.id() == null) {
      throw new IllegalStateException(
          "Zammad createTicket did not return created ticket ID in response.");
    }
    return response.id();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TicketResponse(Long id, String number) {}
}
