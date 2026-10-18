package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.Link;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NegotiationLinkUtil {

  private static final String UI_LINK_REL = "ui";

  /**
   * Returns the {@code "ui"} HATEOAS link href, or empty when the negotiation or the link is
   * absent.
   */
  public static Optional<String> uiLink(EntityModelNegotiationDTO negotiation) {
    if (negotiation == null || negotiation.getLinks() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(negotiation.getLinks().get(UI_LINK_REL)).map(Link::getHref);
  }
}
