package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HashUtilTest {

  @ParameterizedTest
  @CsvSource({
    "'', e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "abc, ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  })
  void sha256Hex_knownInput_returnsExpectedDigest(String input, String expected) {
    assertThat(HashUtil.sha256Hex(input)).isEqualTo(expected);
  }

  @Test
  void sha256Hex_isDeterministic() {
    assertThat(HashUtil.sha256Hex("negotiator-link"))
        .isEqualTo(HashUtil.sha256Hex("negotiator-link"));
  }

  @Test
  void sha256Hex_differentInputs_produceDifferentDigests() {
    assertThat(HashUtil.sha256Hex("a")).isNotEqualTo(HashUtil.sha256Hex("b"));
  }

  @Test
  void sha256Hex_alwaysReturns64LowercaseHexChars() {
    assertThat(HashUtil.sha256Hex("any text")).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void sha256Hex_encodesAsUtf8() {
    assertThat(HashUtil.sha256Hex("ä"))
        .isEqualTo("33e6d73fee82904c8d7afb78de1154d1e8dc2a0edb08120e63df5b9385c2d9cc");
  }
}
