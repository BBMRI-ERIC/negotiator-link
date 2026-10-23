package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credentials for HTTP Basic authentication on the management port where {@code
 * /actuator/prometheus} is exposed.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "management.security")
public class ManagementSecurityProperties {

  @NotBlank private String user;
  @NotBlank private String password;
}
