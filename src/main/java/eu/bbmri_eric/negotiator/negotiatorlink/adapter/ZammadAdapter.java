package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter.buildTitle;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class ZammadAdapter implements HelpDeskAdapter {

  private static final String HMAC_ALGORITHM = "HmacSHA1";
  private static final String SIGNATURE_HEADER = "X-Hub-Signature";
  private static final String SIGNATURE_PREFIX = "sha1=";
  private static final String AUTH_HEADER = "Authorization";
  private static final String TICKET_ARTICLES_PATH = "/api/v1/ticket_articles";
  private static final String TICKETS_PATH = "/api/v1/tickets";
  private static final String LINKS_ADD_PATH = "/api/v1/links/add";

  private final RestClient.Builder restClientBuilder;

  public ZammadAdapter(RestClient.Builder restClientBuilder) {
    this.restClientBuilder = restClientBuilder;
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
    RestClient client = buildClient(config);

    String title = buildTitle(request);
    String group = (String) config.platformConfig().get("group");

    Map<String, Object> article = new HashMap<>();
    article.put("subject", title);
    article.put("body", request.ticketBody());
    article.put("type", "note");
    article.put("internal", false);

    Map<String, Object> body = new HashMap<>();
    body.put("title", title);
    body.put("group", group);
    body.put("negotiation_state", request.negotiationState());
    body.put("negotiator_resource_state", request.resourceState());
    body.put("negotiator_resource_transition_event", null);
    body.put("article", article);

    @SuppressWarnings("unchecked")
    Map<String, Object> response =
        client
            .post()
            .uri(TICKETS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);

    String ticketId = String.valueOf(((Number) response.get("id")).longValue());
    String ticketNumber = String.valueOf(response.get("number"));
    log.info("Created Zammad ticket id={} number={} title='{}'", ticketId, ticketNumber, title);
    return new CreatedTicket(ticketId, ticketNumber);
  }

  @Override
  public void updateNegotiationState(
      String ticketId, String negotiationState, OrganizationConfig config) {
    RestClient client = buildClient(config);
    Map<String, Object> body = Map.of("negotiation_state", negotiationState);
    client
        .put()
        .uri(TICKETS_PATH + "/{id}", ticketId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public void updateResourceState(
      String ticketId, String resourceState, OrganizationConfig config) {
    RestClient client = buildClient(config);
    Map<String, Object> body = new HashMap<>();
    body.put("negotiator_resource_state", resourceState);
    body.put("negotiator_resource_transition_event", null);
    client
        .put()
        .uri(TICKETS_PATH + "/{id}", ticketId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public void addComment(String ticketId, String commentText, OrganizationConfig config) {
    postArticle(ticketId, commentText, false, config);
  }

  @Override
  public void addSystemComment(String ticketId, String message, OrganizationConfig config) {
    postArticle(ticketId, message, true, config);
  }

  @Override
  public void linkRelatedTickets(List<CreatedTicket> tickets, OrganizationConfig config) {
    if (tickets.size() < 2) {
      return;
    }
    RestClient client = buildClient(config);
    for (int i = 0; i < tickets.size(); i++) {
      for (int j = i + 1; j < tickets.size(); j++) {
        CreatedTicket source = tickets.get(i);
        CreatedTicket target = tickets.get(j);
        Map<String, Object> body = new HashMap<>();
        body.put("link_type", "normal");
        body.put("link_object_source", "Ticket");
        body.put("link_object_source_number", source.ticketNumber());
        body.put("link_object_target", "Ticket");
        body.put("link_object_target_value", Long.parseLong(target.ticketId()));
        client
            .post()
            .uri(LINKS_ADD_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
      }
    }
  }

  @Override
  public Optional<HelpdeskCallbackEvent> parseCallback(Map<String, Object> payload) {
    // TODO: not implemented yet
    return Optional.empty();
  }

  private void postArticle(
      String ticketId, String text, boolean internal, OrganizationConfig config) {
    RestClient client = buildClient(config);
    Map<String, Object> body = new HashMap<>();
    body.put("ticket_id", Long.parseLong(ticketId));
    body.put("body", text);
    body.put("type", "note");
    body.put("internal", internal);
    client
        .post()
        .uri(TICKET_ARTICLES_PATH)
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
}
