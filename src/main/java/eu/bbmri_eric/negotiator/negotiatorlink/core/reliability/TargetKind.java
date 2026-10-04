package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/** The kind of entity an outbound intent targets. */
public enum TargetKind {
  /** A help desk ticket (the {@code targetId} is a helpdesk ticket id). */
  TICKET,
  /** A Negotiator negotiation (the {@code targetId} is a negotiationId). */
  NEGOTIATION
}
