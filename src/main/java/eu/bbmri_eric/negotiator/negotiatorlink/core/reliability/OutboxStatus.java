package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Lifecycle status of a durable outbound intent. */
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

  private final boolean terminal;

  OutboxStatus(boolean terminal) {
    this.terminal = terminal;
  }

  /** Whether an intent in this status has reached a final state and will not be delivered again. */
  public boolean isTerminal() {
    return terminal;
  }

  /**
   * Statuses in which an intent still awaits (or is mid-) delivery. Single source of truth for "a
   * CREATE_TICKET is still in process" — shared by the pending-ticket cache's warm-up and its
   * conditional removal so the two never drift. Unordered: use only for {@code IN (:statuses)}
   * membership checks, never where ordering matters.
   */
  public static final Set<OutboxStatus> NONTERMINAL =
      Arrays.stream(values())
          .filter(status -> !status.isTerminal())
          .collect(Collectors.toUnmodifiableSet());
}
