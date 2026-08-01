package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.bbmri_eric.negotiator.negotiatorlink.core.verification.ZammadWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ZammadWebhookVerifierTest {

  private ZammadWebhookVerifier verifier;

  @BeforeEach
  void setUp() {
    verifier = new ZammadWebhookVerifier();
  }

  @Test
  void verify_whenCalled_throwsUnsupportedOperationException() {
    assertThatThrownBy(() -> verifier.verify(new MockHttpServletRequest(), new byte[0]))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void checkAndMarkSeen_whenFirstCall_returnsFalse() {
    assertThat(verifier.checkAndMarkSeen("new-id-1")).isFalse();
  }

  @Test
  void checkAndMarkSeen_whenCalledTwice_returnsTrueOnSecond() {
    verifier.checkAndMarkSeen("new-id-2");
    assertThat(verifier.checkAndMarkSeen("new-id-2")).isTrue();
  }
}
