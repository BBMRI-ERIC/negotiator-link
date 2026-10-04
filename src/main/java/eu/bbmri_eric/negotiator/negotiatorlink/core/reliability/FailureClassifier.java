package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Centralized mapping from an outbound call failure to a {@link FailureClass}. */
@Component
public class FailureClassifier {

  private static final int TOO_MANY_REQUESTS = 429;

  /** Classifies a throwable thrown by an outbound client call. */
  public FailureClass classify(Throwable error) {
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
}
