package eu.bbmri_eric.negotiator.negotiatorlink.common.model;

/**
 * Visibility of a post relayed from a help desk into the Negotiator.
 *
 * <ul>
 *   <li>{@link #PRIVATE} — posted to the private channel in negotiation.
 *   <li>{@link #PUBLIC} — visible to all negotiation participants.
 * </ul>
 */
public enum PostType {
  PRIVATE,
  PUBLIC
}
