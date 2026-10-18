package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationLinkUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.Link;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NegotiationLinkUtilTest {

  @Test
  void uiLink_nullNegotiation_returnsEmpty() {
    assertThat(NegotiationLinkUtil.uiLink(null)).isEmpty();
  }

  @Test
  void uiLink_nullLinks_returnsEmpty() {
    var negotiation = new EntityModelNegotiationDTO();
    negotiation.setLinks(null);
    assertThat(NegotiationLinkUtil.uiLink(negotiation)).isEmpty();
  }

  @Test
  void uiLink_uiPresent_returnsHref() {
    assertThat(NegotiationLinkUtil.uiLink(negotiationWithLink("ui", "http://nego/abc")))
        .contains("http://nego/abc");
  }

  @Test
  void uiLink_onlySelfPresent_returnsEmpty() {
    assertThat(NegotiationLinkUtil.uiLink(negotiationWithLink("self", "http://nego/abc")))
        .isEmpty();
  }

  @Test
  void uiLink_uiPresentButHrefNull_returnsEmpty() {
    assertThat(NegotiationLinkUtil.uiLink(negotiationWithLink("ui", null))).isEmpty();
  }

  private EntityModelNegotiationDTO negotiationWithLink(String rel, String href) {
    Link link = new Link();
    link.setHref(href);
    EntityModelNegotiationDTO negotiation = new EntityModelNegotiationDTO();
    negotiation.setLinks(Map.of(rel, link));
    return negotiation;
  }
}
