package eu.bbmri_eric.negotiator.negotiatorlink.common.model;

import jakarta.annotation.Nonnull;
import java.util.List;

/** Request to create a help desk ticket. */
public record CreateTicketRequest(
    String title,
    String negotiationState,
    String resourceState,
    String ticketBody,
    @Nonnull List<Attachment> attachments) {}
