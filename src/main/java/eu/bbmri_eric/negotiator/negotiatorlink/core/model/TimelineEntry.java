package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

import java.time.LocalDateTime;

/** A single Negotiator timeline log entry. */
public record TimelineEntry(String triggeredBy, String text, LocalDateTime timestamp) {}
