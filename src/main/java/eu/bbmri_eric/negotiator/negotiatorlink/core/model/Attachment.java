package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

/** Internal representation of a file to forward into a help desk ticket. */
public record Attachment(String filename, String contentType, byte[] content) {}
