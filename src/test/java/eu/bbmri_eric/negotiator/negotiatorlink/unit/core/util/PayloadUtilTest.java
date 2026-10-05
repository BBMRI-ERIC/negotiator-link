package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil;
import org.junit.jupiter.api.Test;

class PayloadUtilTest {

  private record Simple(String name, int value) {}

  @Test
  void serialize_simpleRecord_producesValidJson() {
    String json = PayloadUtil.serialize(new Simple("test", 42));

    assertThat(json).isEqualTo("{\"name\":\"test\",\"value\":42}");
  }

  @Test
  void serialize_nullPayload_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> PayloadUtil.serialize(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Payload must not be null");
  }
}
