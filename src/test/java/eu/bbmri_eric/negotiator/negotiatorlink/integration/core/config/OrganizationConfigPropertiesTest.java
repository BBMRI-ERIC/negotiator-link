package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrganizationConfigPropertiesTest {

  @Autowired private OrganizationConfigRegistry registry;

  @Test
  void allFieldsBind_whenTestYamlLoaded_returnsFullyPopulatedConfig() {
    var config = registry.findByExternalId("bbmri:DE:MHH");
    assertThat(config).isPresent();
    assertThat(config.get().organizationExternalId()).isEqualTo("bbmri:DE:MHH");
    assertThat(config.get().type()).isEqualTo(HelpDeskType.ZAMMAD);
    assertThat(config.get().ticketStrategy()).isEqualTo(TicketStrategy.PER_RESOURCE);
    assertThat(config.get().baseUrl()).isEqualTo("https://zammad.mhh.de");
    assertThat(config.get().credentials()).containsEntry("apiToken", "test-api-token");
    assertThat(config.get().callbackSecret()).isEqualTo("test-callback-secret");
    assertThat(config.get().platformConfig()).containsEntry("group", "Sales");
  }

  @Test
  void findAll_whenTestYamlLoaded_returnsBothTestOrgs() {
    assertThat(registry.findAll()).hasSize(2);
  }
}
