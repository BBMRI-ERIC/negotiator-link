package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationLinkUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.Link;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NegotiationLinkUtilTest {

  @Test
  void getLink_nullNegotiation_returnsEmpty() {
    assertThat(NegotiationLinkUtil.getLink(null)).isEmpty();
  }

  @Test
  void getLink_nullLinks_returnsEmpty() {
    var negotiation = new EntityModelNegotiationDTO();
    negotiation.setLinks(null);
    assertThat(NegotiationLinkUtil.getLink(negotiation)).isEmpty();
  }

  @Test
  void uiLink_getPresent_returnsHref() {
    assertThat(NegotiationLinkUtil.getLink(negotiationWithLink("ui", "http://nego/abc")))
        .contains("http://nego/abc");
  }

  @Test
  void getLink_onlySelfPresent_returnsEmpty() {
    assertThat(NegotiationLinkUtil.getLink(negotiationWithLink("self", "http://nego/abc")))
        .isEmpty();
  }

  @Test
  void uiLink_getPresentButHrefNull_returnsEmpty() {
    assertThat(NegotiationLinkUtil.getLink(negotiationWithLink("ui", null))).isEmpty();
  }

  private EntityModelNegotiationDTO negotiationWithLink(String rel, String href) {
    Link link = new Link();
    link.setHref(href);
    EntityModelNegotiationDTO negotiation = new EntityModelNegotiationDTO();
    negotiation.setLinks(Map.of(rel, link));
    return negotiation;
  }
}
