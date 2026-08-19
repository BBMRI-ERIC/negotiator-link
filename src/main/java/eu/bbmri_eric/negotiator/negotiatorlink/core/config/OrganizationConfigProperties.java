package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "negotiatorlink")
public class OrganizationConfigProperties {

  private NegotiatorProperties negotiator = new NegotiatorProperties();
  private List<OrgEntry> organizations = new ArrayList<>();

  @Data
  public static class NegotiatorProperties {
    private String baseUrl;
  }

  @Data
  public static class OrgEntry {

    private String organizationExternalId;
    private HelpDeskType type;
    private TicketStrategy ticketStrategy;
    private String baseUrl;
    private Map<String, String> credentials = new HashMap<>();
    private String callbackSecret;
    private Map<String, Object> platformConfig = new HashMap<>();
    private NegotiatorCredentialsEntry negotiatorCredentials = new NegotiatorCredentialsEntry();
  }

  @Data
  public static class NegotiatorCredentialsEntry {
    private String clientId;
    private String clientSecret;
  }
}
