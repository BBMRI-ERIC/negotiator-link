package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/** Lifecycle status of a durable outbound intent. */
public enum OutboxStatus {
  /** Awaiting delivery. Claimable by the outbox deliverer. */
  PENDING,
  /** Claimed by a poller and currently being delivered. */
  IN_PROGRESS,
  /** Successfully delivered to the external system. */
  DELIVERED,
  /** Permanently rejected (a non-retryable 4xx) -> not retried. */
  FAILED_PERMANENT,
  /** Exhausted its retry budget or failed with unexpected error. */
  DEAD_LETTER
}
