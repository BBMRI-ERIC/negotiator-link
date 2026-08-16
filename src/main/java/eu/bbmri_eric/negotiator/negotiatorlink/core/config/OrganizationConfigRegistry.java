package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(OrganizationConfigProperties.class)
public class OrganizationConfigRegistry {

  private static final Map<HelpDeskType, Set<String>> REQUIRED_PLATFORM_KEYS =
      Map.of(
          HelpDeskType.ZAMMAD, Set.of("group"),
          HelpDeskType.ALVAO, Set.of());

  private final List<OrganizationConfig> configs;

  public OrganizationConfigRegistry(OrganizationConfigProperties properties) {
    this.configs =
        properties.getOrganizations().stream().map(OrganizationConfigRegistry::toConfig).toList();
    validate(this.configs);
    log.info("Loaded {} organization configuration(s)", this.configs.size());
  }

  public Optional<OrganizationConfig> findByExternalId(String organizationExternalId) {
    return configs.stream()
        .filter(c -> c.organizationExternalId().equals(organizationExternalId))
        .findFirst();
  }

  public List<OrganizationConfig> findAll() {
    return configs;
  }

  private static OrganizationConfig toConfig(OrganizationConfigProperties.OrgEntry entry) {
    var nc = entry.getNegotiatorCredentials();
    return new OrganizationConfig(
        entry.getOrganizationExternalId(),
        entry.getType(),
        entry.getTicketStrategy(),
        entry.getBaseUrl(),
        entry.getCredentials(),
        entry.getCallbackSecret(),
        entry.getPlatformConfig(),
        new NegotiatorCredentials(nc.getClientId(), nc.getClientSecret()));
  }

  private static void validate(List<OrganizationConfig> configs) {
    for (OrganizationConfig config : configs) {
      String orgId = config.organizationExternalId();
      if (StringUtils.isBlank(config.callbackSecret())) {
        throw new IllegalStateException(
            "Organization '%s': callbackSecret must not be blank".formatted(orgId));
      }
      for (Map.Entry<String, String> credential : config.credentials().entrySet()) {
        if (StringUtils.isBlank(credential.getValue())) {
          throw new IllegalStateException(
              "Organization '%s': credentials.%s must not be blank"
                  .formatted(orgId, credential.getKey()));
        }
      }
      Set<String> requiredKeys = REQUIRED_PLATFORM_KEYS.getOrDefault(config.type(), Set.of());
      for (String key : requiredKeys) {
        if (!config.platformConfig().containsKey(key) || config.platformConfig().get(key) == null) {
          throw new IllegalStateException(
              "Organization '%s': platformConfig.%s is required for type %s"
                  .formatted(orgId, key, config.type()));
        }
      }
      NegotiatorCredentials nc = config.negotiatorCredentials();
      if (StringUtils.isBlank(nc.clientId())) {
        throw new IllegalStateException(
            "Organization '%s': negotiatorCredentials.clientId must not be blank".formatted(orgId));
      }
      if (StringUtils.isBlank(nc.clientSecret())) {
        throw new IllegalStateException(
            "Organization '%s': negotiatorCredentials.clientSecret must not be blank"
                .formatted(orgId));
      }
    }
  }
}
