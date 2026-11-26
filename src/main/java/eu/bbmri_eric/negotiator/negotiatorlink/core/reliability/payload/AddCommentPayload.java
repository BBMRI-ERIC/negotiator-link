package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

import eu.bbmri_eric.negotiator.negotiatorlink.common.model.CommentType;

/** Body of an {@code ADD_COMMENT} intent. */
public record AddCommentPayload(String text, CommentType commentType) {}
