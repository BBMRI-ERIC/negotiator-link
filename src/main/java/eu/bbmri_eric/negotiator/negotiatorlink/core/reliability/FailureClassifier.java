package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class FailureClassifier {

  private static final int TOO_MANY_REQUESTS = 429;

  private static final String REQUIRED_FORM_DETAIL =
      "The requirement for this operation was not met. Please make sure you have submitted the "
          + "required form and try again.";

  private final ObjectMapper objectMapper;

  public FailureClassifier(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Classifies a throwable thrown by an outbound client call or during inbox processing. */
  public FailureClass classify(Throwable error) {
    if (error instanceof PendingTicketCreationException) {
      return FailureClass.TRANSIENT;
    }
    if (error instanceof RestClientResponseException httpError) {
      int status = httpError.getStatusCode().value();
      if (status == TOO_MANY_REQUESTS || status >= 500) {
        return FailureClass.TRANSIENT;
      }
      if (status >= 400) {
        return FailureClass.PERMANENT;
      }
      return FailureClass.TRANSIENT;
    }
    if (error instanceof ResourceAccessException) {
      // connect/read timeout, connection reset, DNS, etc.
      return FailureClass.TRANSIENT;
    }
    return FailureClass.UNEXPECTED_ERROR;
  }

  /**
   * Decides if error occured because a resource state transition needs a required form to be
   * submitted first.
   */
  public boolean isRequiredFormNotSubmitted(Throwable error) {
    if (!(error instanceof RestClientResponseException httpError)) {
      return false;
    }
    if (httpError.getStatusCode().value() != 400) {
      return false;
    }
    String body = httpError.getResponseBodyAsString();
    if (body == null || body.isBlank()) {
      return false;
    }
    try {
      JsonNode problem = objectMapper.readTree(body);
      return REQUIRED_FORM_DETAIL.equals(problem.path("detail").asText(null));
    } catch (JacksonException e) {
      return false;
    }
  }
}
