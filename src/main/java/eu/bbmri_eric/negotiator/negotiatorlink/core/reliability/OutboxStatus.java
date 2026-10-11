package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;

/** Lifecycle status of a durable outbound intent. */
@Getter
public enum OutboxStatus {
  /** Awaiting delivery. Claimable by the outbox deliverer. */
  PENDING(false),
  /** Claimed by a poller and currently being delivered. */
  IN_PROCESS(false),
  /** Successfully delivered to the external system. */
  DELIVERED(true),
  /** Permanently rejected (a non-retryable 4xx) -> not retried. */
  FAILED_PERMANENT(true),
  /** Exhausted its retry budget or failed with unexpected error. */
  DEAD_LETTER(true);

  /** Whether an intent in this status has reached a final state and will not be delivered again. */
  private final boolean terminal;

  OutboxStatus(boolean terminal) {
    this.terminal = terminal;
  }

  /** Statuses in which an intent still awaits (or is mid-) delivery. */
  public static final Set<OutboxStatus> NON_TERMINAL =
      Arrays.stream(values())
          .filter(status -> !status.isTerminal())
          .collect(Collectors.toUnmodifiableSet());
}
