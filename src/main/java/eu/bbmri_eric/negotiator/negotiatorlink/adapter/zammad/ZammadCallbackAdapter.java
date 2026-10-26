package eu.bbmri_eric.negotiator.negotiatorlink.adapter.zammad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskCallbackAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskAttachmentRef;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionWithPostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketStateChangedEvent;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Inbound Zammad integration: verifies webhook authenticity and translates Zammad callbacks into
 * the middleware's internal {@link HelpdeskCallbackEvent} model.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZammadCallbackAdapter implements HelpdeskCallbackAdapter {

  private static final String HMAC_ALGORITHM = "HmacSHA1";
  private static final String SIGNATURE_HEADER = "X-Hub-Signature";
  private static final String SIGNATURE_PREFIX = "sha1=";
  private static final String DELIVERY_HEADER = "X-Zammad-Delivery";
  private static final String TICKET_ATTACHMENTS_DOWNLOAD_PATH =
      "/api/v1/ticket_attachment/%s/%s/%s";

  private final ObjectMapper objectMapper;

  @Override
  public HelpdeskType getType() {
    return HelpdeskType.ZAMMAD;
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

    if (Boolean.TRUE.equals(parsed.ticket().stateUpdated())) {
      return parseTicketStateChange(ticketId, parsed.ticket().state());
    }

    String transitionEvent = normalize(parsed.ticket().transitionEvent());
    String resourceState = normalize(parsed.ticket().resourceState());
    boolean hasArticle = hasArticle(parsed.article());

    if (transitionEvent == null && !hasArticle) {
      log.debug("Zammad callback for ticket '{}' has no actionable content — discarding", ticketId);
      return Optional.empty();
    }

    HelpdeskCallbackType callbackType = resolveType(transitionEvent != null, hasArticle, parsed);
    if (callbackType.equals(HelpdeskCallbackType.POST_MESSAGE) && parsed.article().internal()) {
      log.warn("Zammad callback for ticket '{}' is an internal comment — discarding", ticketId);
      return Optional.empty();
    }

    Sender sender = parseSender(parsed.article() != null ? parsed.article().createdBy() : null);
    List<HelpdeskAttachmentRef> attachments = parseAttachments(ticketId, parsed.article());

    return Optional.of(
        switch (callbackType) {
          case RESOURCE_TRANSITION ->
              new ResourceTransitionEvent(ticketId, resourceState, transitionEvent);
          case POST_MESSAGE ->
              new PostMessageEvent(ticketId, parsed.article().body(), sender, attachments);
          case RESOURCE_TRANSITION_WITH_POST_MESSAGE ->
              new ResourceTransitionWithPostMessageEvent(
                  ticketId,
                  resourceState,
                  transitionEvent,
                  parsed.article().body(),
                  sender,
                  attachments);
          default ->
              throw new IllegalStateException(
                  "Unsupported callback type " + callbackType + " for ticket " + ticketId);
        });
  }

  private Optional<HelpdeskCallbackEvent> parseTicketStateChange(String ticketId, String state) {
    String ticketState = normalize(state);
    if (ticketState == null) {
      log.warn("Zammad ticket state change for ticket '{}' has no state — discarding", ticketId);
      return Optional.empty();
    }
    return Optional.of(new TicketStateChangedEvent(ticketId, ticketState));
  }

  private static HelpdeskCallbackType resolveType(
      boolean hasTransition, boolean hasArticle, ZammadCallbackPayload parsed) {
    if (hasTransition && hasArticle) {
      return parsed.article().internal()
          ? HelpdeskCallbackType.RESOURCE_TRANSITION
          : HelpdeskCallbackType.RESOURCE_TRANSITION_WITH_POST_MESSAGE;
    }
    return hasTransition
        ? HelpdeskCallbackType.RESOURCE_TRANSITION
        : HelpdeskCallbackType.POST_MESSAGE;
  }

  private static String normalize(String value) {
    return StringUtils.isBlank(value) ? null : value;
  }

  private static boolean hasArticle(ZammadCallbackPayload.Article article) {
    return article != null && article.body() != null;
  }

  private static Sender parseSender(ZammadCallbackPayload.ArticleUser user) {
    if (user == null) {
      return null;
    }
    String name = buildFullName(user.firstname(), user.lastname());
    return new Sender(name, user.email());
  }

  private static String buildFullName(String firstName, String lastName) {
    if (firstName == null && lastName == null) return null;
    if (firstName == null) return lastName;
    if (lastName == null) return firstName;
    return firstName + " " + lastName;
  }

  private static List<HelpdeskAttachmentRef> parseAttachments(
      String ticketId, ZammadCallbackPayload.Article article) {
    if (article == null || article.attachments() == null || article.id() == null) {
      return List.of();
    }
    return article.attachments().stream()
        .filter(attachment -> attachment != null && attachment.id() != null)
        .map(
            attachment ->
                new HelpdeskAttachmentRef(
                    buildDownloadPath(ticketId, article.id(), attachment.id()),
                    attachment.filename(),
                    attachment.contentType()))
        .toList();
  }

  private static String buildDownloadPath(String ticketId, String articleId, String attachmentId) {
    return String.format(TICKET_ATTACHMENTS_DOWNLOAD_PATH, ticketId, articleId, attachmentId);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ZammadCallbackPayload(Ticket ticket, Article article) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ticket(
        String id,
        @JsonProperty(ZammadFields.RESOURCE_STATE) String resourceState,
        @JsonProperty(ZammadFields.TRANSITION_EVENT) String transitionEvent,
        @JsonProperty(ZammadFields.STATE) String state,
        @JsonProperty(ZammadFields.STATE_UPDATED) Boolean stateUpdated) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Article(
        String id,
        String body,
        boolean internal,
        @JsonProperty("created_by") ArticleUser createdBy,
        List<ArticleAttachment> attachments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArticleAttachment(String id, String filename, Preferences preferences) {
      String contentType() {
        return preferences == null ? null : preferences.contentType();
      }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Preferences(@JsonProperty("Content-Type") String contentType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArticleUser(String firstname, String lastname, String email) {}
  }
}
