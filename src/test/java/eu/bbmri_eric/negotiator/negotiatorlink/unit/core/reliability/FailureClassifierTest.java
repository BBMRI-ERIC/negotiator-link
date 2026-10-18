package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.FailureClass;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.FailureClassifier;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class FailureClassifierTest {

  private static final String STATUS_TEXT = "status text";
  private static final String REQUIRED_FORM_DETAIL =
      "The requirement for this operation was not met. Please make sure you have submitted the "
          + "required form and try again.";

  private final FailureClassifier classifier = new FailureClassifier(new ObjectMapper());

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 502, 503, 504})
  void classify_retryableHttpStatus_isTransient(int status) {
    assertThat(classify(status)).isEqualTo(FailureClass.TRANSIENT);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 409, 422})
  void classify_clientErrorHttpStatus_isPermanent(int status) {
    assertThat(classify(status)).isEqualTo(FailureClass.PERMANENT);
  }

  @ParameterizedTest
  @ValueSource(ints = {100, 200, 301})
  void classify_nonErrorHttpStatus_isTransient(int status) {
    assertThat(classify(status)).isEqualTo(FailureClass.TRANSIENT);
  }

  @Test
  void classify_resourceAccessException_isTransient() {
    assertThat(classifier.classify(new ResourceAccessException("connection reset")))
        .isEqualTo(FailureClass.TRANSIENT);
  }

  @Test
  void classify_pendingTicketCreationException_isTransient() {
    assertThat(classifier.classify(new PendingTicketCreationException("neg-1")))
        .isEqualTo(FailureClass.TRANSIENT);
  }

  @Test
  void classify_unknownThrowable_isUnexpectedError() {
    assertThat(classifier.classify(new IllegalStateException("boom")))
        .isEqualTo(FailureClass.UNEXPECTED_ERROR);
  }

  @Test
  void isRequiredFormNotSubmitted_matchingDetail_returnsTrue() {
    assertThat(classifier.isRequiredFormNotSubmitted(problem400(REQUIRED_FORM_DETAIL))).isTrue();
  }

  @Test
  void isRequiredFormNotSubmitted_nonMatchingDetail_returnsFalse() {
    assertThat(classifier.isRequiredFormNotSubmitted(problem400("some other detail"))).isFalse();
  }

  @Test
  void isRequiredFormNotSubmitted_non400_returnsFalse() {
    HttpClientErrorException notFound =
        HttpClientErrorException.create(
            HttpStatus.NOT_FOUND,
            "Not Found",
            HttpHeaders.EMPTY,
            problemBody(REQUIRED_FORM_DETAIL).getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    assertThat(classifier.isRequiredFormNotSubmitted(notFound)).isFalse();
  }

  @Test
  void isRequiredFormNotSubmitted_plainException_returnsFalse() {
    assertThat(classifier.isRequiredFormNotSubmitted(new RuntimeException("boom"))).isFalse();
  }

  @Test
  void isRequiredFormNotSubmitted_blankBody_returnsFalse() {
    HttpClientErrorException emptyBody =
        HttpClientErrorException.create(
            HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, null, null);
    assertThat(classifier.isRequiredFormNotSubmitted(emptyBody)).isFalse();
  }

  private HttpClientErrorException problem400(String detail) {
    return HttpClientErrorException.create(
        HttpStatus.BAD_REQUEST,
        "Bad Request",
        HttpHeaders.EMPTY,
        problemBody(detail).getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  private String problemBody(String detail) {
    return "{\"type\":\"about:blank\",\"title\":\"Could not advance the state machine\","
        + "\"status\":400,\"detail\":\""
        + detail
        + "\"}";
  }

  private FailureClass classify(int status) {
    return classifier.classify(httpError(status));
  }

  private static RestClientResponseException httpError(int status) {
    return new RestClientResponseException(
        STATUS_TEXT, HttpStatus.valueOf(status), STATUS_TEXT, null, null, null);
  }
}
