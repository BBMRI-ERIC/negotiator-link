package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/**
 * Origin of a captured inbound event. Stored as the {@code source} half of the inbox dedup key
 * {@code (source, delivery_id)}. For helpdesk callbacks the concrete source is the organization's
 * external id.
 */
public enum EventSource {
  NEGOTIATOR
}
