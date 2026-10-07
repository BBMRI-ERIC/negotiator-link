package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.FailureClass;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.FailureClassifier;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class FailureClassifierTest {

  private static final String STATUS_TEXT = "status text";

  private final FailureClassifier classifier = new FailureClassifier();

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

  private FailureClass classify(int status) {
    return classifier.classify(httpError(status));
  }

  private static RestClientResponseException httpError(int status) {
    return new RestClientResponseException(
        STATUS_TEXT, HttpStatus.valueOf(status), STATUS_TEXT, null, null, null);
  }
}
