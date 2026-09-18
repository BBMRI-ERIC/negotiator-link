package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class NegotiatorLinkPropertiesTest {

  @Autowired private OrganizationConfigRegistry registry;

  @Test
  void allFieldsBind_whenTestYamlLoaded_returnsFullyPopulatedConfig() {
    var config = registry.findByExternalId("bbmri:DE:MHH");
    assertThat(config).isPresent();
    assertThat(config.get().organizationExternalId()).isEqualTo("bbmri:DE:MHH");
    assertThat(config.get().type()).isEqualTo(HelpdeskType.ZAMMAD);
    assertThat(config.get().ticketStrategy()).isEqualTo(TicketStrategy.PER_RESOURCE);
    assertThat(config.get().baseUrl()).isEqualTo("https://zammad.mhh.de");
    assertThat(config.get().credentials()).containsEntry("apiToken", "test-api-token");
    assertThat(config.get().callbackSecret()).isEqualTo("test-callback-secret");
    assertThat(config.get().platformConfig()).containsEntry("group", "Sales");
    assertThat(config.get().negotiationStateToTicketState()).containsEntry("CONCLUDED", "closed");
    assertThat(config.get().resourceStateToTicketState())
        .containsEntry("RESOURCE_MADE_AVAILABLE", "closed");
    assertThat(config.get().ticketStateToResourceEvent())
        .containsEntry("open", "MARK_AS_CHECKING_AVAILABILITY");
    assertThat(config.get().negotiatorCredentials().clientId()).isEqualTo("test-client-id-mhh");
    assertThat(config.get().negotiatorCredentials().clientSecret())
        .isEqualTo("test-client-secret-mhh");
  }

  @Test
  void stateMaps_whenOmittedFromYaml_defaultToEmptyMaps() {
    var config = registry.findByExternalId("bbmri:CZ:MMCI");
    assertThat(config).isPresent();
    assertThat(config.get().negotiationStateToTicketState()).isEmpty();
    assertThat(config.get().resourceStateToTicketState()).isEmpty();
    assertThat(config.get().ticketStateToResourceEvent()).isEmpty();
  }

  @Test
  void findAll_whenTestYamlLoaded_returnsAllTestOrgs() {
    assertThat(registry.findAll()).hasSize(3);
  }
}
