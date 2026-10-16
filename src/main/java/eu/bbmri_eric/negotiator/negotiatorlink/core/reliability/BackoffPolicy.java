package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Exponential backoff with full jitter, capped. Shared by the inbox and outbox retry. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BackoffPolicy {

  /**
   * Returned delay grows as {@code base * 2^(attempt-1)}, capped at {@code max}, then full-jittered
   * into {@code [cap/2, cap]} to avoid synchronized retries.
   */
  public static Duration nextDelay(int attempt, Duration base, Duration max) {
    long exponential = base.toMillis() * (1L << Math.min(Math.max(attempt - 1, 0), 20));
    long capped = Math.min(exponential, max.toMillis());
    long jittered = ThreadLocalRandom.current().nextLong(capped / 2 + 1, capped + 1);
    return Duration.ofMillis(jittered);
  }
}
