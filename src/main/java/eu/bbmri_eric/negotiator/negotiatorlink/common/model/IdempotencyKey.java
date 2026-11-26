package eu.bbmri_eric.negotiator.negotiatorlink.common.model;

/**
 * Opaque per-logical-action key that a adapter implementations <em>may</em> use to deduplicate a
 * retried write.
 *
 * <p>Derived from the {@code outbox_intent} row id, which is stable across retries of the same
 * intent, so the resulting key is too.
 */
public record IdempotencyKey(String value) {

  private static final String PREFIX = "nl-oi-";

  /**
   * Build the key for an outbox intent row id.
   *
   * @param outboxRowId the {@code outbox_intent} row id this action is delivered from
   * @return the idempotency key for that action
   */
  public static IdempotencyKey fromOutboxRowId(long outboxRowId) {
    return new IdempotencyKey(PREFIX + outboxRowId);
  }
}
