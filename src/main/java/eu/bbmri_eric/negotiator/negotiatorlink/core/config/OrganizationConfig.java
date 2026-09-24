package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.util.Map;

public record OrganizationConfig(
    String organizationExternalId,
    HelpdeskType type,
    TicketStrategy ticketStrategy,
    String baseUrl,
    Map<String, String> credentials,
    String callbackSecret,
    Map<String, Object> platformConfig,
    Map<String, String> negotiationStateToTicketState,
    Map<String, String> resourceStateToTicketState,
    NegotiatorCredentials negotiatorCredentials) {

  public OrganizationConfig {
    credentials = Map.copyOf(credentials);
    platformConfig = Map.copyOf(platformConfig);
    negotiationStateToTicketState = Map.copyOf(negotiationStateToTicketState);
    resourceStateToTicketState = Map.copyOf(resourceStateToTicketState);
  }
}
