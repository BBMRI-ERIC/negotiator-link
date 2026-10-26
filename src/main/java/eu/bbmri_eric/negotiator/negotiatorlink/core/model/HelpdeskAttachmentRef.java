package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

/**
 * A reference to an attachment on an inbound helpdesk post
 *
 * @param downloadPath the helpdesk-specific <em>relative</em> path from which the attachment can be
 *     downloaded.
 * @param filename the original file name
 * @param contentType the MIME type of the attachment
 */
public record HelpdeskAttachmentRef(String downloadPath, String filename, String contentType) {}
