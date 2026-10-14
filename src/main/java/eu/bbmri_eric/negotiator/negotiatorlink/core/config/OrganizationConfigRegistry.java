package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(NegotiatorLinkProperties.class)
public class OrganizationConfigRegistry {

  private static final Map<HelpdeskType, Set<String>> REQUIRED_PLATFORM_KEYS =
      Map.of(
          HelpdeskType.ZAMMAD, Set.of("group", "botEmail"),
          HelpdeskType.ALVAO, Set.of());

  private final List<OrganizationConfig> configs;

  public OrganizationConfigRegistry(NegotiatorLinkProperties properties) {
    this.configs =
        properties.getOrganizations().stream().map(entry -> toConfig(entry, properties)).toList();
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

  private static OrganizationConfig toConfig(
      NegotiatorLinkProperties.OrgEntry entry, NegotiatorLinkProperties properties) {
    var nc = entry.getNegotiatorCredentials();
    NegotiatorLinkProperties.HelpdeskDefaults typeDefaults =
        properties.getHelpdeskDefaults().get(entry.getType());
    TicketStrategy ticketStrategy =
        entry.getTicketStrategy() != null
            ? entry.getTicketStrategy()
            : properties.getOrganizationDefaults().getTicketStrategy();
    return new OrganizationConfig(
        entry.getOrganizationExternalId(),
        entry.getType(),
        ticketStrategy,
        entry.getBaseUrl(),
        entry.getCredentials(),
        entry.getCallbackSecret(),
        entry.getPlatformConfig(),
        resolveMap(
            entry.getNegotiationStateToTicketState(),
            typeDefaults,
            NegotiatorLinkProperties.HelpdeskDefaults::getNegotiationStateToTicketState),
        resolveMap(
            entry.getResourceStateToTicketState(),
            typeDefaults,
            NegotiatorLinkProperties.HelpdeskDefaults::getResourceStateToTicketState),
        resolveMap(
            entry.getTicketStateToResourceEvent(),
            typeDefaults,
            NegotiatorLinkProperties.HelpdeskDefaults::getTicketStateToResourceEvent),
        new NegotiatorCredentials(nc.getClientId(), nc.getClientSecret()));
  }

  private static Map<String, String> resolveMap(
      Map<String, String> orgValue,
      NegotiatorLinkProperties.HelpdeskDefaults typeDefaults,
      Function<NegotiatorLinkProperties.HelpdeskDefaults, Map<String, String>> getter) {
    if (orgValue != null) {
      return orgValue;
    }
    if (typeDefaults != null) {
      Map<String, String> defaultValue = getter.apply(typeDefaults);
      if (defaultValue != null) {
        return defaultValue;
      }
    }
    return Map.of();
  }

  private static void validate(List<OrganizationConfig> configs) {
    for (OrganizationConfig config : configs) {
      String orgId = config.organizationExternalId();
      if (StringUtils.isBlank(config.baseUrl())) {
        throw new IllegalStateException(
            "Organization '%s': baseUrl must not be blank".formatted(orgId));
      }
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
