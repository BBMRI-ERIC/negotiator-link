package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.util.Map;

public record OrganizationConfig(
    String organizationExternalId,
    HelpDeskType type,
    TicketStrategy ticketStrategy,
    String baseUrl,
    Map<String, String> credentials,
    String callbackSecret,
    Map<String, Object> platformConfig) {

  public OrganizationConfig {
    credentials = Map.copyOf(credentials);
    platformConfig = Map.copyOf(platformConfig);
  }
}
