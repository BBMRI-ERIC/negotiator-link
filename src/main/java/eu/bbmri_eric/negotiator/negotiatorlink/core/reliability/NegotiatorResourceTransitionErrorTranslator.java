package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorProblem;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Translates a Negotiator error response, raised while delivering a resource-transition outbox
 * intent, into a help desk comment, but only for the explicitly configured errors.
 */
@Slf4j
@Component
public class NegotiatorResourceTransitionErrorTranslator {

  private static final String COMMENT_LEAD =
      "The requested resource transition did not succeed because of the following reason:";
  private static final String UNMET_REQUIREMENT_SUFFIX = "lifecycle/unmet-requirement";
  private static final String UNMET_REQUIREMENT_HINT =
      "Please fill out the Information Requirement form in the Negotiator first.";

  private final Set<String> knownTypeSuffixes;
  private final ObjectMapper objectMapper;

  public NegotiatorResourceTransitionErrorTranslator(
      NegotiatorLinkProperties properties, ObjectMapper objectMapper) {
    this.knownTypeSuffixes = Set.copyOf(properties.getNegotiator().getTransitionErrorTypes());
    this.objectMapper = objectMapper;
  }

  /**
   * Returns a help desk comment describing the rejection if {@code error} is a recognized
   * Negotiator transition error, or {@link Optional#empty()} if the error is not configured for
   * reporting (or its body cannot be parsed).
   */
  public Optional<String> translate(RestClientResponseException error) {
    NegotiatorProblem problem = parse(error);
    if (problem == null || !isKnown(problem.type())) {
      return Optional.empty();
    }
    return Optional.of(buildComment(problem));
  }

  private NegotiatorProblem parse(RestClientResponseException error) {
    String body = error.getResponseBodyAsString();
    if (StringUtils.isBlank(body)) {
      return null;
    }
    try {
      return objectMapper.readValue(body, NegotiatorProblem.class);
    } catch (Exception e) {
      log.debug("Negotiator error body is not a parseable problem: {}", e.getMessage());
      return null;
    }
  }

  /** Matches a configured suffix against the tail of the {@code type} URI. */
  private boolean isKnown(String type) {
    if (type == null) {
      return false;
    }
    return knownTypeSuffixes.stream().anyMatch(type::endsWith);
  }

  private String buildComment(NegotiatorProblem problem) {
    StringBuilder comment = new StringBuilder(COMMENT_LEAD);
    if (StringUtils.isNotBlank(problem.detail())) {
      comment.append('\n').append(problem.detail());
    } else if (StringUtils.isNotBlank(problem.title())) {
      comment.append('\n').append(problem.title());
    }

    if (problem.type().endsWith(UNMET_REQUIREMENT_SUFFIX)) {
      comment.append("\n\n").append(UNMET_REQUIREMENT_HINT);
    }
    return comment.toString();
  }
}
