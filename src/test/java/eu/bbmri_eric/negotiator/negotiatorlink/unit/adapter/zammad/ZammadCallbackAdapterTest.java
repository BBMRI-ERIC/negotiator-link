package eu.bbmri_eric.negotiator.negotiatorlink.unit.adapter.zammad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.zammad.ZammadCallbackAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionWithPostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

class ZammadCallbackAdapterTest {

  private static final String CALLBACK_SECRET = "test-callback-secret";
  private static final String SIGNATURE_HEADER = "X-Hub-Signature";

  private ZammadCallbackAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new ZammadCallbackAdapter(new ObjectMapper());
  }

  @Test
  void getType_returnsZammadType() {
    assertThat(adapter.getType()).isEqualTo(HelpdeskType.ZAMMAD);
  }

  @Test
  void verifyCallback_validSignature_returnsTrue() throws Exception {
    byte[] body = "{\"ticket\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.set(SIGNATURE_HEADER, "sha1=" + TestUtil.hmacSha1Hex(CALLBACK_SECRET, body));

    assertThat(adapter.verifyCallback(headers, body, CALLBACK_SECRET)).isTrue();
  }

  @Test
  void verifyCallback_wrongSignature_returnsFalse() {
    byte[] body = "{\"ticket\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.set(SIGNATURE_HEADER, "sha1=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

    assertThat(adapter.verifyCallback(headers, body, CALLBACK_SECRET)).isFalse();
  }

  @Test
  void verifyCallback_missingHeader_returnsFalse() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertThat(adapter.verifyCallback(new HttpHeaders(), body, CALLBACK_SECRET)).isFalse();
  }

  @Test
  void verifyCallback_malformedHex_returnsFalse() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.set(SIGNATURE_HEADER, "sha1=not-valid-hex!!!");

    assertThat(adapter.verifyCallback(headers, body, CALLBACK_SECRET)).isFalse();
  }

  @Test
  void parseCallback_resourceTransitionOnly_returnsResourceTransitionEvent() {
    var payload = zammadCallbackPayload("1", "MARK_AS_CHECKING_AVAILABILITY", "IN_PROGRESS");

    var expected = new ResourceTransitionEvent("1", "IN_PROGRESS", "MARK_AS_CHECKING_AVAILABILITY");
    assertThat(adapter.parseCallback(payload)).contains(expected);
  }

  @Test
  void parseCallback_publicArticleOnly_returnsPostMessage() {
    var payload =
        zammadCallbackPayload(
            "2", "", "IN_PROGRESS", "Hello from help desk", "John", "Rep", "john.rep@example.com");

    var expected =
        new PostMessageEvent(
            "2", "Hello from help desk", new Sender("John Rep", "john.rep@example.com"));
    assertThat(adapter.parseCallback(payload)).contains(expected);
  }

  @Test
  void parseCallback_bothArticleAndTransition_returnsCombinedType() {
    var payload =
        zammadCallbackPayload(
            "3",
            "MARK_AS_AVAILABLE",
            "IN_PROGRESS",
            "Granting access",
            "Jane",
            "Rep",
            "jane.rep@example.com");

    var expected =
        new ResourceTransitionWithPostMessageEvent(
            "3",
            "IN_PROGRESS",
            "MARK_AS_AVAILABLE",
            "Granting access",
            new Sender("Jane Rep", "jane.rep@example.com"));
    assertThat(adapter.parseCallback(payload)).contains(expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("emptyResultPayloads")
  void parseCallback_returnsEmpty(String description, Map<String, Object> payload) {
    assertThat(adapter.parseCallback(payload)).isEmpty();
  }

  static Stream<Arguments> emptyResultPayloads() {
    Map<String, Object> internalArticleOnly =
        zammadCallbackPayload("5", "", "IN_PROGRESS", "Internal note", "Jane", "Doe", "jd@ex.com");
    @SuppressWarnings("unchecked")
    Map<String, Object> internalArticle = (Map<String, Object>) internalArticleOnly.get("article");
    internalArticle.put("internal", true);

    return Stream.of(
        Arguments.of("null payload", null),
        Arguments.of("missing ticket node", Map.of("article", new HashMap<>())),
        Arguments.of(
            "missing ticket id",
            payloadWithTicket(ticketNode(null, "MARK_AS_AVAILABLE", "IN_PROGRESS"))),
        Arguments.of("no transition and no article", zammadCallbackPayload("4", "", "IN_PROGRESS")),
        Arguments.of("blank transition and no article", zammadCallbackPayload("4", "   ", null)),
        Arguments.of("internal comment only", internalArticleOnly));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("senderPayloads")
  void parseCallback_parsesSender(
      String description, String firstName, String lastName, String email, Sender expectedSender) {
    var payload = zammadCallbackPayload("9", "", "IN_PROGRESS", "Body", firstName, lastName, email);

    assertThat(adapter.parseCallback(payload))
        .isPresent()
        .get()
        .asInstanceOf(type(PostMessageEvent.class))
        .extracting(PostMessageEvent::sender)
        .isEqualTo(expectedSender);
  }

  static Stream<Arguments> senderPayloads() {
    return Stream.of(
        Arguments.of(
            "both names present",
            "John",
            "Rep",
            "john.rep@example.com",
            new Sender("John Rep", "john.rep@example.com")),
        Arguments.of(
            "only first name",
            "John",
            null,
            "john@example.com",
            new Sender("John", "john@example.com")),
        Arguments.of(
            "only last name", null, "Rep", "rep@example.com", new Sender("Rep", "rep@example.com")),
        Arguments.of(
            "no name, only email",
            null,
            null,
            "anon@example.com",
            new Sender(null, "anon@example.com")),
        Arguments.of("no name and no email", null, null, null, new Sender(null, null)));
  }

  @Test
  void parseCallback_articleWithoutCreatedBy_hasNullSender() {
    Map<String, Object> payload = zammadCallbackPayload("10", "", "IN_PROGRESS");
    @SuppressWarnings("unchecked")
    Map<String, Object> article = (Map<String, Object>) payload.get("article");
    article.put("body", "Body without author");

    assertThat(adapter.parseCallback(payload))
        .isPresent()
        .get()
        .asInstanceOf(type(PostMessageEvent.class))
        .extracting(PostMessageEvent::sender)
        .isNull();
  }

  @Test
  void extractCallbackId_headerAbsent_returnsEmpty() {
    assertThat(adapter.extractCallbackId(new HttpHeaders())).isEmpty();
  }

  @Test
  void extractCallbackId_differentIds_returnsDifferentValues() {
    HttpHeaders first = new HttpHeaders();
    first.set("X-Zammad-Delivery", "delivery-1");

    HttpHeaders second = new HttpHeaders();
    second.set("X-Zammad-Delivery", "delivery-2");

    assertThat(adapter.extractCallbackId(first)).contains("delivery-1");
    assertThat(adapter.extractCallbackId(second)).contains("delivery-2");
  }

  private static Map<String, Object> ticketNode(
      String ticketId, String transitionEvent, String resourceState) {
    Map<String, Object> ticket = new HashMap<>();
    ticket.put("id", ticketId);
    ticket.put("negotiator_resource_transition_event", transitionEvent);
    ticket.put("negotiator_resource_state", resourceState);
    return ticket;
  }

  private static Map<String, Object> payloadWithTicket(Map<String, Object> ticket) {
    return Map.of("ticket", ticket, "article", new HashMap<>());
  }

  private static Map<String, Object> zammadCallbackPayload(
      String ticketId,
      String transitionEvent,
      String resourceState,
      String articleBody,
      String firstName,
      String lastName,
      String email) {
    Map<String, Object> ticket = ticketNode(ticketId, transitionEvent, resourceState);
    Map<String, Object> article = new HashMap<>();
    if (articleBody != null) {
      article.put("body", articleBody);
      Map<String, Object> createdBy = new HashMap<>();
      createdBy.put("firstname", firstName);
      createdBy.put("lastname", lastName);
      createdBy.put("email", email);
      article.put("created_by", createdBy);
    }
    return Map.of("ticket", ticket, "article", article);
  }

  private static Map<String, Object> zammadCallbackPayload(
      String ticketId, String transitionEvent, String resourceState) {
    return zammadCallbackPayload(ticketId, transitionEvent, resourceState, null, null, null, null);
  }
}
