package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

/**
 * Visibility of a post relayed from a help desk into the Negotiator.
 *
 * <ul>
 *   <li>{@link #PRIVATE} — scoped to the originating organization's audience.
 *   <li>{@link #PUBLIC} — visible to all negotiation participants.
 * </ul>
 */
public enum PostType {
  PRIVATE,
  PUBLIC
}
