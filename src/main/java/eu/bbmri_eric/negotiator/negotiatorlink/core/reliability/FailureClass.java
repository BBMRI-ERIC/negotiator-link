package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/** Classification of an outbound call failure. */
public enum FailureClass {
  /** 5xx, 429, timeouts, connection errors. */
  TRANSIENT,
  /** 4xx errors (except 429). */
  PERMANENT,
  /** Retry budget exhausted or a recurring unexpected error. */
  UNEXPECTED_ERROR
}
