package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class NegotiatorLinkPropertiesTest {

  @Autowired private OrganizationConfigRegistry registry;
  @Autowired private NegotiatorLinkProperties properties;

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
  void stateMaps_whenExplicitlyEmpty_resolveToEmptyMaps() {
    var config = registry.findByExternalId("bbmri:CZ:MMCI");
    assertThat(config).isPresent();
    assertThat(config.get().negotiationStateToTicketState()).isEmpty();
    assertThat(config.get().resourceStateToTicketState()).isEmpty();
    assertThat(config.get().ticketStateToResourceEvent()).isEmpty();
  }

  @Test
  void stateMaps_whenAbsentFromOrg_inheritsTypeDefaults() {
    var config = registry.findByExternalId("bbmri:FI:FIMM");
    assertThat(config).isPresent();
    assertThat(config.get().negotiationStateToTicketState())
        .containsEntry("ABANDONED", "closed")
        .containsEntry("CONCLUDED", "closed");
    assertThat(config.get().resourceStateToTicketState())
        .containsEntry("RESOURCE_NOT_MADE_AVAILABLE", "closed")
        .containsEntry("RESOURCE_MADE_AVAILABLE", "closed");
    assertThat(config.get().ticketStateToResourceEvent())
        .containsEntry("open", "MARK_AS_CHECKING_AVAILABILITY");
  }

  @Test
  void findAll_whenTestYamlLoaded_returnsAllTestOrgs() {
    assertThat(registry.findAll()).hasSize(4);
  }

  @Test
  void inboxProperties_whenConfigured_bindAllFields() {
    NegotiatorLinkProperties.InboxProperties inbox = properties.getInbox();
    assertThat(inbox.getBatchSize()).isEqualTo(25);
    assertThat(inbox.getPollDelayMs()).isEqualTo(500);
    assertThat(inbox.getMaxAttempts()).isEqualTo(3);
    assertThat(inbox.getBaseBackoff()).isEqualTo(Duration.parse("PT2S"));
    assertThat(inbox.getMaxBackoff()).isEqualTo(Duration.parse("PT60S"));
  }

  @Test
  void outboxProperties_whenConfigured_bindAllFields() {
    NegotiatorLinkProperties.OutboxProperties outbox = properties.getOutbox();
    assertThat(outbox.getBatchSize()).isEqualTo(10);
    assertThat(outbox.getPollDelayMs()).isEqualTo(200);
    assertThat(outbox.getMaxAttempts()).isEqualTo(4);
    assertThat(outbox.getBaseBackoff()).isEqualTo(Duration.parse("PT3S"));
    assertThat(outbox.getMaxBackoff()).isEqualTo(Duration.parse("PT120S"));
    assertThat(outbox.getClaimTimeout()).isEqualTo(Duration.parse("PT55S"));
  }
}
