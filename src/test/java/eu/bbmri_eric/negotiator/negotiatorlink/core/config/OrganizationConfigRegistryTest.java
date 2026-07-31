package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrganizationConfigRegistryTest {

  @Test
  void findByExternalId_whenOrgExists_returnsConfig() {
    var registry = new OrganizationConfigRegistry(propsWithEntry(zammadEntry()));
    var result = registry.findByExternalId("bbmri:DE:MHH");
    assertThat(result).isPresent();
    assertThat(result.get().organizationExternalId()).isEqualTo("bbmri:DE:MHH");
    assertThat(result.get().type()).isEqualTo(HelpDeskType.ZAMMAD);
    assertThat(result.get().ticketStrategy()).isEqualTo(TicketStrategy.PER_RESOURCE);
    assertThat(result.get().credentials()).containsEntry("apiToken", "secret-token");
    assertThat(result.get().platformConfig()).containsEntry("groupId", 3);
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
        .hasMessageContaining("bbmri:DE:MHH")
        .hasMessageContaining("callbackSecret");
  }

  @Test
  void constructor_whenCredentialValueIsBlank_throws() {
    var entry = zammadEntry();
    entry.setCredentials(new HashMap<>(Map.of("apiToken", "")));
    assertThatThrownBy(() -> new OrganizationConfigRegistry(propsWithEntry(entry)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bbmri:DE:MHH")
        .hasMessageContaining("apiToken");
  }

  @Test
  void constructor_whenRequiredPlatformConfigKeyMissing_throws() {
    var entry = zammadEntry();
    entry.setPlatformConfig(new HashMap<>());
    assertThatThrownBy(() -> new OrganizationConfigRegistry(propsWithEntry(entry)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bbmri:DE:MHH")
        .hasMessageContaining("groupId");
  }

  @Test
  void constructor_whenOrganizationsListIsEmpty_succeeds() {
    var props = new OrganizationConfigProperties();
    var registry = new OrganizationConfigRegistry(props);
    assertThat(registry.findAll()).isEmpty();
  }

  private static OrganizationConfigProperties.OrgEntry zammadEntry() {
    var entry = new OrganizationConfigProperties.OrgEntry();
    entry.setOrganizationExternalId("bbmri:DE:MHH");
    entry.setType(HelpDeskType.ZAMMAD);
    entry.setTicketStrategy(TicketStrategy.PER_RESOURCE);
    entry.setBaseUrl("https://zammad.mhh.de");
    entry.setCredentials(new HashMap<>(Map.of("apiToken", "secret-token")));
    entry.setCallbackSecret("secret-callback");
    entry.setPlatformConfig(new HashMap<>(Map.of("groupId", 3)));
    return entry;
  }

  private static OrganizationConfigProperties propsWithEntry(
      OrganizationConfigProperties.OrgEntry entry) {
    var props = new OrganizationConfigProperties();
    props.setOrganizations(List.of(entry));
    return props;
  }
}
