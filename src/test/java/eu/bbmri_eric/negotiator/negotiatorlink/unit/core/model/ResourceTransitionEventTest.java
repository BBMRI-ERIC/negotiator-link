package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.NegotiatorResourceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NegotiatorResourceEventTest {

  @ParameterizedTest
  @CsvSource({
    "MARK_AS_CHECKING_AVAILABILITY, CHECKING_AVAILABILITY",
    "STEP_AWAY, RESOURCE_UNAVAILABLE",
    "MARK_AS_UNAVAILABLE, RESOURCE_UNAVAILABLE",
    "MARK_AS_CURRENTLY_UNAVAILABLE_BUT_WILLING_TO_COLLECT, RESOURCE_UNAVAILABLE_WILLING_TO_COLLECT",
    "MARK_AS_AVAILABLE, RESOURCE_AVAILABLE",
    "INDICATE_ACCESS_CONDITIONS, ACCESS_CONDITIONS_INDICATED",
    "GRANT_ACCESS_TO_RESOURCE, RESOURCE_MADE_AVAILABLE"
  })
  void fromEvent_knownEvent_returnsCorrectTargetState(String event, String expectedState) {
    var result = NegotiatorResourceEvent.fromEvent(event);
    assertThat(result).isPresent();
    assertThat(result.get().targetState()).isEqualTo(expectedState);
  }

  @Test
  void fromEvent_unknownEvent_returnsEmpty() {
    assertThat(NegotiatorResourceEvent.fromEvent("SOME_UNKNOWN_EVENT")).isEmpty();
  }

  @Test
  void fromEvent_null_returnsEmpty() {
    assertThat(NegotiatorResourceEvent.fromEvent(null)).isEmpty();
  }
}
