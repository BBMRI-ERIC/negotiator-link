package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "negotiator-link")
public class NegotiatorLinkProperties {

  private List<OrgEntry> organizations = new ArrayList<>();
  private Map<String, String> resourceEvents = new HashMap<>();

  @Data
  public static class OrgEntry {

    private String organizationExternalId;
    private HelpdeskType type;
    private TicketStrategy ticketStrategy;
    private String baseUrl;
    private Map<String, String> credentials = new HashMap<>();
    private String callbackSecret;
    private Map<String, Object> platformConfig = new HashMap<>();
    private Map<String, String> negotiationStateToTicketState = new HashMap<>();
    private Map<String, String> resourceStateToTicketState = new HashMap<>();
    private Map<String, String> ticketStateToResourceEvent = new HashMap<>();
    private NegotiatorCredentialsEntry negotiatorCredentials = new NegotiatorCredentialsEntry();
  }

  @Data
  public static class NegotiatorCredentialsEntry {
    private String clientId;
    private String clientSecret;
  }
}
