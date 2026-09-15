package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.ReplayGuardImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayGuardImplTest {

  private ReplayGuardImpl replayGuard;

  @BeforeEach
  void setUp() {
    replayGuard = new ReplayGuardImpl();
  }

  @Test
  void alreadySeen_whenFirstCall_returnsFalse() {
    assertThat(replayGuard.alreadySeen("new-id-1")).isFalse();
  }

  @Test
  void alreadySeen_whenCalledTwice_returnsTrueOnSecond() {
    replayGuard.alreadySeen("new-id-2");
    assertThat(replayGuard.alreadySeen("new-id-2")).isTrue();
  }

  @Test
  void alreadySeen_whenDifferentKeys_areTrackedIndependently() {
    assertThat(replayGuard.alreadySeen("id-a")).isFalse();
    assertThat(replayGuard.alreadySeen("id-b")).isFalse();
    assertThat(replayGuard.alreadySeen("id-a")).isTrue();
  }
}
