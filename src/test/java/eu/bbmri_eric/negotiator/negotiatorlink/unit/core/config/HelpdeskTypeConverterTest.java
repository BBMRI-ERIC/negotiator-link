package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.bbmri_eric.negotiator.negotiatorlink.common.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskTypeConverter;
import org.junit.jupiter.api.Test;

class HelpdeskTypeConverterTest {

  private final HelpdeskTypeConverter converter = new HelpdeskTypeConverter();

  @Test
  void convert_whenKnownType_returnsEnum() {
    assertThat(converter.convert("ZAMMAD")).isEqualTo(HelpdeskType.ZAMMAD);
  }

  @Test
  void convert_whenLowerCaseOrPadded_isNormalized() {
    assertThat(converter.convert("  zammad ")).isEqualTo(HelpdeskType.ZAMMAD);
  }

  @Test
  void convert_whenUnsupportedType_throwsWithActionableMessage() {
    assertThatThrownBy(() -> converter.convert("SOME_NOT_SUPPORTED_TYPE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Unsupported helpdesk type 'SOME_NOT_SUPPORTED_TYPE'. Supported types:")
        .hasMessageContaining("ZAMMAD");
  }
}
