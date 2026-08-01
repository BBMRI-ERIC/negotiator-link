package eu.bbmri_eric.negotiator.negotiatorlink.core.verification;

public class WebhookAuthenticationException extends RuntimeException {

  public WebhookAuthenticationException(String message) {
    super(message);
  }

  public WebhookAuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
