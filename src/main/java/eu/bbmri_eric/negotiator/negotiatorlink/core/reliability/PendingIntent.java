package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/**
 * An outbound operation a handler decides to perform, before it is persisted to the outbox.
 *
 * @param intentType the operation to perform
 * @param targetKind whether {@code targetId} is a ticket or a negotiation
 * @param targetId the call target (helpdesk ticket id or negotiationId); may be {@code null} when
 *     ticket does not exist in created time yet
 * @param negotiationId of the negotiation this intent is related to
 * @param organizationExtId used to re-resolve config/adapter at delivery time
 * @param payloadJson the platform-neutral intent payload
 */
public record PendingIntent(
    IntentType intentType,
    TargetKind targetKind,
    String targetId,
    String negotiationId,
    String organizationExtId,
    String payloadJson) {}
