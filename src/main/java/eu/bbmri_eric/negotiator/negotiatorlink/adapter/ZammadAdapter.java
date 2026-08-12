package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class ZammadAdapter implements HelpDeskAdapter {

  private static final String AUTH_HEADER = "Authorization";
  private static final String TICKET_ARTICLES_PATH = "/api/v1/ticket_articles";
  private static final String TICKETS_PATH = "/api/v1/tickets";
  private static final String TICKET_BY_ID_PATH = TICKETS_PATH + "/{id}";
  private static final String LINKS_ADD_PATH = "/api/v1/links/add";

  // Callback verification
  private static final String HMAC_ALGORITHM = "HmacSHA1";
  private static final String SIGNATURE_HEADER = "X-Hub-Signature";
  private static final String SIGNATURE_PREFIX = "sha1=";
  private static final String DELIVERY_HEADER = "X-Zammad-Delivery";

  // Zammad JSON field names
  private static final String FIELD_NEGOTIATION_STATE = "negotiation_state";
  private static final String FIELD_RESOURCE_STATE = "negotiator_resource_state";
  private static final String FIELD_TRANSITION_EVENT = "negotiator_resource_transition_event";
  private static final String ARTICLE_TYPE_NOTE = "note";

  private final RestClient.Builder restClientBuilder;
  private final ObjectMapper objectMapper;

  public ZammadAdapter(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
    this.restClientBuilder = restClientBuilder;
    this.objectMapper = objectMapper;
  }

  @Override
  public HelpDeskType getType() {
    return HelpDeskType.ZAMMAD;
  }

  @Override
  public Optional<String> extractCallbackId(HttpHeaders headers) {
    return Optional.ofNullable(headers.getFirst(DELIVERY_HEADER));
  }

  @Override
  public boolean verifyCallback(HttpHeaders headers, byte[] body, String callbackSecret) {
    String signatureHeader = headers.getFirst(SIGNATURE_HEADER);
    if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
      log.debug("Missing or malformed {} header", SIGNATURE_HEADER);
      return false;
    }
    String receivedHex = signatureHeader.substring(SIGNATURE_PREFIX.length());
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] computed = mac.doFinal(body);
      byte[] receivedBytes = HexFormat.of().parseHex(receivedHex);
      return MessageDigest.isEqual(computed, receivedBytes);
    } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
      log.warn("Callback signature verification error: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public CreatedTicket createTicket(CreateTicketRequest request, OrganizationConfig config) {
    Map<String, Object> body = buildCreateTicketBody(request, config);

    @SuppressWarnings("unchecked")
    Map<String, Object> response =
        buildClient(config)
            .post()
            .uri(TICKETS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);

    CreatedTicket ticket = parseCreatedTicket(response);
    log.info(
        "Created Zammad ticket id={} number={} title='{}'",
        ticket.ticketId(),
        ticket.ticketNumber(),
        request.title());
    return ticket;
  }

  @Override
  public void updateNegotiationState(
      String ticketId, String negotiationState, OrganizationConfig config) {
    putTicket(ticketId, Map.of(FIELD_NEGOTIATION_STATE, negotiationState), config);
  }

  @Override
  public void updateResourceState(
      String ticketId, String resourceState, OrganizationConfig config) {
    Map<String, Object> body = new HashMap<>();
    body.put(FIELD_RESOURCE_STATE, resourceState);
    body.put(FIELD_TRANSITION_EVENT, null);
    putTicket(ticketId, body, config);
  }

  @Override
  public void addComment(String ticketId, String commentText, OrganizationConfig config) {
    postArticle(ticketId, commentText, false, config);
  }

  @Override
  public void addSystemComment(
      String ticketId, String systemCommentText, OrganizationConfig config) {
    postArticle(ticketId, systemCommentText, true, config);
  }

  @Override
  public void linkRelatedTickets(List<CreatedTicket> tickets, OrganizationConfig config) {
    if (tickets.size() < 2) {
      return;
    }
    RestClient client = buildClient(config);
    for (int i = 0; i < tickets.size(); i++) {
      for (int j = i + 1; j < tickets.size(); j++) {
        post(client, LINKS_ADD_PATH, buildLinkBody(tickets.get(i), tickets.get(j)));
      }
    }
  }

  @Override
  public Optional<HelpdeskCallbackEvent> parseCallback(Map<String, Object> payload) {
    if (payload == null) {
      return Optional.empty();
    }

    ZammadCallbackPayload parsed = objectMapper.convertValue(payload, ZammadCallbackPayload.class);

    if (parsed.ticket() == null || parsed.ticket().id() == null) {
      log.warn("Zammad callback payload missing 'ticket.id' — discarding");
      return Optional.empty();
    }

    String ticketId = parsed.ticket().id();
    String transitionEvent = normalize(parsed.ticket().transitionEvent());
    boolean hasArticle = hasArticle(parsed.article());

    if (transitionEvent == null && !hasArticle) {
      log.debug("Zammad callback for ticket '{}' has no actionable content — discarding", ticketId);
      return Optional.empty();
    }

    String postText = hasArticle ? parsed.article().body() : null;
    String helpdeskActor = hasArticle ? parsed.article().from() : null;

    return Optional.of(
        new HelpdeskCallbackEvent(
            ticketId,
            resolveType(transitionEvent != null, hasArticle),
            transitionEvent,
            postText,
            helpdeskActor));
  }

  private Map<String, Object> buildCreateTicketBody(
      CreateTicketRequest request, OrganizationConfig config) {
    Map<String, Object> article = new HashMap<>();
    article.put("subject", request.title());
    article.put("body", request.ticketBody());
    article.put("type", ARTICLE_TYPE_NOTE);
    article.put("internal", false);

    Map<String, Object> body = new HashMap<>();
    body.put("title", request.title());
    body.put("group", config.platformConfig().get("group"));
    body.put(FIELD_NEGOTIATION_STATE, request.negotiationState());
    body.put(FIELD_RESOURCE_STATE, request.resourceState());
    body.put(FIELD_TRANSITION_EVENT, null);
    body.put("article", article);
    return body;
  }

  private Map<String, Object> buildArticleBody(String ticketId, String text, boolean internal) {
    Map<String, Object> body = new HashMap<>();
    body.put("ticket_id", Long.parseLong(ticketId));
    body.put("body", text);
    body.put("type", ARTICLE_TYPE_NOTE);
    body.put("internal", internal);
    return body;
  }

  private Map<String, Object> buildLinkBody(CreatedTicket source, CreatedTicket target) {
    Map<String, Object> body = new HashMap<>();
    body.put("link_type", "normal");
    body.put("link_object_source", "Ticket");
    body.put("link_object_source_number", source.ticketNumber());
    body.put("link_object_target", "Ticket");
    body.put("link_object_target_value", Long.parseLong(target.ticketId()));
    return body;
  }

  private void postArticle(
      String ticketId, String text, boolean internal, OrganizationConfig config) {
    post(buildClient(config), TICKET_ARTICLES_PATH, buildArticleBody(ticketId, text, internal));
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
    String apiToken = config.credentials().get("apiToken");
    return restClientBuilder
        .clone()
        .baseUrl(config.baseUrl())
        .defaultHeader(AUTH_HEADER, "Token token=" + apiToken)
        .build();
  }

  private CreatedTicket parseCreatedTicket(Map<String, Object> response) {
    if (response == null) {
      throw new IllegalStateException("Zammad createTicket returned an empty response body");
    }
    Object idObj = response.get("id");
    Object numberObj = response.get("number");
    if (!(idObj instanceof Number id) || numberObj == null) {
      throw new IllegalStateException(
          "Zammad createTicket response missing 'id' or 'number' — response: " + response);
    }
    return new CreatedTicket(String.valueOf(id.longValue()), String.valueOf(numberObj));
  }

  private static HelpdeskCallbackType resolveType(boolean hasTransition, boolean hasArticle) {
    if (hasTransition && hasArticle) {
      return HelpdeskCallbackType.RESOURCE_TRANSITION_WITH_POST_MESSAGE;
    }
    return hasTransition
        ? HelpdeskCallbackType.RESOURCE_TRANSITION
        : HelpdeskCallbackType.POST_MESSAGE;
  }

  /** Treats blank transition events as absent. */
  private static String normalize(String value) {
    return StringUtils.isBlank(value) ? null : value;
  }

  private static boolean hasArticle(ZammadCallbackPayload.Article article) {
    return article != null && article.body() != null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ZammadCallbackPayload(Ticket ticket, Article article) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ticket(String id, @JsonProperty(FIELD_TRANSITION_EVENT) String transitionEvent) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Article(String body, String from) {}
  }
}
