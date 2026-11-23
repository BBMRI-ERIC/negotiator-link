package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.util.Arrays;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Converts the configured help desk type string into a {@link HelpdeskType}. */
@Component
@ConfigurationPropertiesBinding
public class HelpdeskTypeConverter implements Converter<String, HelpdeskType> {

  @Override
  @NonNull
  public HelpdeskType convert(@NonNull String source) {
    try {
      return HelpdeskType.valueOf(source.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unsupported helpdesk type '%s'. Supported types: %s"
              .formatted(source, Arrays.toString(HelpdeskType.values())));
    }
  }
}
