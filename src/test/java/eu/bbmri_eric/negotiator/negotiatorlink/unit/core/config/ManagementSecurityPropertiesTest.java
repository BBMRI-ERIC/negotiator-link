package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.ManagementSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ManagementSecurityPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(ManagementSecurityProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void contextFails_whenManagementUserIsBlank() {
    runner
        .withPropertyValues("management.security.user=", "management.security.password=secret")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void contextFails_whenManagementPasswordIsBlank() {
    runner
        .withPropertyValues("management.security.user=admin", "management.security.password=")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void contextStarts_whenBothCredentialsPresent() {
    runner
        .withPropertyValues("management.security.user=admin", "management.security.password=secret")
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }
}
