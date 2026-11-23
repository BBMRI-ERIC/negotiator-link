package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.NegotiatorTransitionErrorTranslator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

class NegotiatorTransitionErrorTranslatorTest {

  private static final String STATUS_TEXT = "Conflict";

  private final NegotiatorTransitionErrorTranslator translator =
      new NegotiatorTransitionErrorTranslator(
          properties(
              List.of(
                  "lifecycle/domain-state-conflict",
                  "lifecycle/unmet-requirement",
                  "lifecycle/no-transition")),
          new ObjectMapper());

  @Test
  void translate_domainStateConflict_returnsLeadThenDetailOnNextLine() {
    String body =
        problem(
            "http://localhost:8081/api/v3/problems/lifecycle/domain-state-conflict",
            "A Guard blocks this Transition",
            "Mark as Available cannot fire while the Negotiation is Paused, only while it is In Progress.");

    Optional<String> comment = translator.translate(httpError(body));

    assertThat(comment)
        .contains(
            """
            The requested resource transition did not succeed because of the following reason:
            Mark as Available cannot fire while the Negotiation is Paused, only while it is In Progress.""");
    assertThat(comment.orElseThrow())
        .doesNotContain("A Guard blocks this Transition")
        .doesNotContain("Please fill out the Information Requirement form");
  }

  @Test
  void translate_unmetRequirement_appendsFormInstructionAfterBlankLine() {
    String body =
        problem(
            "http://localhost:8081/api/v3/problems/lifecycle/unmet-requirement",
            "Information Requirement not met",
            "Mark as Available cannot fire for this Resource until its Information Requirement form is submitted.");

    assertThat(translator.translate(httpError(body)))
        .contains(
            """
            The requested resource transition did not succeed because of the following reason:
            Mark as Available cannot fire for this Resource until its Information Requirement form is submitted.

            Please fill out the Information Requirement form in the Negotiator first.""");
  }

  @Test
  void translate_detailMissing_fallsBackToTitle() {
    String body =
        """
        {"type":"http://localhost:8081/api/v3/problems/lifecycle/no-transition",\
        "title":"No Transition for this Event","status":409,"instance":"/api/v3/negotiations/x"}""";

    assertThat(translator.translate(httpError(body)))
        .contains(
            """
            The requested resource transition did not succeed because of the following reason:
            No Transition for this Event""");
  }

  @Test
  void translate_matchesSuffixIndependentlyOfInstanceBaseUrl() {
    String body =
        problem(
            "https://negotiator.prod.example.org/api/v3/problems/lifecycle/no-transition",
            "No Transition for this Event",
            "The Resource is Checking Availability, and no Transition leaves that State.");

    assertThat(translator.translate(httpError(body))).isPresent();
  }

  @Test
  void translate_unknownType_returnsEmpty() {
    String body =
        problem(
            "http://localhost:8081/api/v3/problems/lifecycle/some-other-error",
            "Some other error",
            "Not configured for reporting.");

    assertThat(translator.translate(httpError(body))).isEmpty();
  }

  @Test
  void translate_malformedBody_returnsEmpty() {
    assertThat(translator.translate(httpError("not json"))).isEmpty();
  }

  @Test
  void translate_emptyBody_returnsEmpty() {
    assertThat(translator.translate(httpError(""))).isEmpty();
  }

  private static NegotiatorLinkProperties properties(List<String> transitionErrorTypes) {
    NegotiatorLinkProperties props = new NegotiatorLinkProperties();
    props.getNegotiator().setTransitionErrorTypes(transitionErrorTypes);
    return props;
  }

  private static String problem(String type, String title, String detail) {
    return """

   {"type":"%s","title":"%s","status":409,"detail":"%s","instance":"/api/v3/negotiations/x"}"""
        .formatted(type, title, detail);
  }

  private static RestClientResponseException httpError(String body) {
    return new RestClientResponseException(
        STATUS_TEXT,
        HttpStatus.CONFLICT,
        STATUS_TEXT,
        null,
        body.getBytes(StandardCharsets.UTF_8),
        null);
  }
}
