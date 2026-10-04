package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/** Lifecycle status of a durably-captured inbound event */
public enum InboxStatus {
  /** Captured, not yet processed. Claimable by the inbox poller. */
  RECEIVED,
  /** Successfully processed. */
  PROCESSED,
  /** Authenticated but non-actionable (no handler, no mapping, unparseable, ...). */
  SKIPPED,
  /** Processing failed after exhausting attempts or permanent failure. */
  FAILED
}
