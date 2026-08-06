package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record CreatedTicket(@Nonnull String ticketId, @Nullable String ticketNumber) {}
