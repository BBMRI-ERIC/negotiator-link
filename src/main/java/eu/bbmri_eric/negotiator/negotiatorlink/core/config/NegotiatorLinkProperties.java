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
  private Map<HelpdeskType, HelpdeskDefaults> defaults = new HashMap<>();

  @Data
  public static class OrgEntry {

    private String organizationExternalId;
    private HelpdeskType type;
    private TicketStrategy ticketStrategy;
    private String baseUrl;
    private Map<String, String> credentials = new HashMap<>();
    private String callbackSecret;
    private Map<String, Object> platformConfig = new HashMap<>();
    // null = absent (inherit type defaults); empty map = explicit opt-out
    private Map<String, String> negotiationStateToTicketState;
    private Map<String, String> resourceStateToTicketState;
    private Map<String, String> ticketStateToResourceEvent;
    private NegotiatorCredentialsEntry negotiatorCredentials = new NegotiatorCredentialsEntry();
  }

  @Data
  public static class HelpdeskDefaults {
    private Map<String, String> negotiationStateToTicketState;
    private Map<String, String> resourceStateToTicketState;
    private Map<String, String> ticketStateToResourceEvent;
  }

  @Data
  public static class NegotiatorCredentialsEntry {
    private String clientId;
    private String clientSecret;
  }
}
