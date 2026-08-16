package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrganizationConfigRegistryTest {

  private static final String ORG_EXTERNAL_ID = "bbmri:DE:MHH";
  private static final String CLIENT_ID = "client-id";
  private static final String CLIENT_SECRET = "client-secret";

  @Test
  void findByExternalId_whenOrgExists_returnsConfig() {
    var registry = new OrganizationConfigRegistry(propsWithEntry(zammadEntry()));
    var result = registry.findByExternalId(ORG_EXTERNAL_ID);
    assertThat(result).isPresent();
    assertThat(result.get().organizationExternalId()).isEqualTo(ORG_EXTERNAL_ID);
    assertThat(result.get().type()).isEqualTo(HelpDeskType.ZAMMAD);
    assertThat(result.get().ticketStrategy()).isEqualTo(TicketStrategy.PER_RESOURCE);
    assertThat(result.get().credentials()).containsEntry("apiToken", "secret-token");
    assertThat(result.get().platformConfig()).containsEntry("group", "Sales");
  }

  @Test
  void findAll_whenSingleOrgConfig_hasOneEntry() {
    var registry = new OrganizationConfigRegistry(propsWithEntry(zammadEntry()));
    assertThat(registry.findAll()).hasSize(1);
  }

  @Test
  void findByExternalId_whenOrgUnknown_returnsEmpty() {
    var registry = new OrganizationConfigRegistry(propsWithEntry(zammadEntry()));
    assertThat(registry.findByExternalId("unknown")).isEmpty();
  }

  @Test
  void constructor_whenCallbackSecretIsBlank_throws() {
    var entry = zammadEntry();
    entry.setCallbackSecret("");
    assertThatThrownBy(() -> new OrganizationConfigRegistry(propsWithEntry(entry)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ORG_EXTERNAL_ID)
        .hasMessageContaining("callbackSecret");
  }

  @Test
  void constructor_whenCredentialValueIsBlank_throws() {
    var entry = zammadEntry();
    entry.setCredentials(new HashMap<>(Map.of("apiToken", "")));
    assertThatThrownBy(() -> new OrganizationConfigRegistry(propsWithEntry(entry)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ORG_EXTERNAL_ID)
        .hasMessageContaining("apiToken");
  }

  @Test
  void constructor_whenRequiredPlatformConfigKeyMissing_throws() {
    var entry = zammadEntry();
    entry.setPlatformConfig(new HashMap<>());
    assertThatThrownBy(() -> new OrganizationConfigRegistry(propsWithEntry(entry)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ORG_EXTERNAL_ID)
        .hasMessageContaining("group");
  }

  @Test
  void constructor_whenOrganizationsListIsEmpty_succeeds() {
    var props = new OrganizationConfigProperties();
    var registry = new OrganizationConfigRegistry(props);
    assertThat(registry.findAll()).isEmpty();
  }

  @Test
  void constructor_whenNegotiatorClientIdIsBlank_throws() {
    var entry = zammadEntry();
    entry.getNegotiatorCredentials().setClientId("");
    assertThatThrownBy(() -> new OrganizationConfigRegistry(propsWithEntry(entry)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ORG_EXTERNAL_ID)
        .hasMessageContaining("clientId");
  }

  @Test
  void constructor_whenNegotiatorClientSecretIsBlank_throws() {
    var entry = zammadEntry();
    entry.getNegotiatorCredentials().setClientSecret("");
    assertThatThrownBy(() -> new OrganizationConfigRegistry(propsWithEntry(entry)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ORG_EXTERNAL_ID)
        .hasMessageContaining("clientSecret");
  }

  @Test
  void findByExternalId_returnsNegotiatorCredentials() {
    var registry = new OrganizationConfigRegistry(propsWithEntry(zammadEntry()));
    var config = registry.findByExternalId(ORG_EXTERNAL_ID).orElseThrow();
    assertThat(config.negotiatorCredentials().clientId()).isEqualTo(CLIENT_ID);
    assertThat(config.negotiatorCredentials().clientSecret()).isEqualTo(CLIENT_SECRET);
  }

  private static OrganizationConfigProperties.OrgEntry zammadEntry() {
    var entry = new OrganizationConfigProperties.OrgEntry();
    entry.setOrganizationExternalId(ORG_EXTERNAL_ID);
    entry.setType(HelpDeskType.ZAMMAD);
    entry.setTicketStrategy(TicketStrategy.PER_RESOURCE);
    entry.setBaseUrl("https://zammad.mhh.de");
    entry.setCredentials(new HashMap<>(Map.of("apiToken", "secret-token")));
    entry.setCallbackSecret("secret-callback");
    entry.setPlatformConfig(new HashMap<>(Map.of("group", "Sales")));
    var creds = new OrganizationConfigProperties.NegotiatorCredentialsEntry();
    creds.setClientId(CLIENT_ID);
    creds.setClientSecret(CLIENT_SECRET);
    entry.setNegotiatorCredentials(creds);
    return entry;
  }

  private static OrganizationConfigProperties propsWithEntry(
      OrganizationConfigProperties.OrgEntry entry) {
    var props = new OrganizationConfigProperties();
    props.setOrganizations(List.of(entry));
    return props;
  }
}
