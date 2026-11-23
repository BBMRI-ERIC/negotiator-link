package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a> problem detail returned by
 * Negotiator on an error response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NegotiatorProblem(
    String type, String title, Integer status, String detail, String instance) {}
