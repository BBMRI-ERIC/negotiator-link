package eu.bbmri_eric.negotiator.negotiatorlink.util;

import com.standardwebhooks.Webhook;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorCredentials;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.OrganizationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.mock.web.MockHttpServletRequest;

public final class TestUtil {

  private TestUtil() {}

  public static String hmacSha1Hex(String secret, byte[] data)
      throws NoSuchAlgorithmException, InvalidKeyException {
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    return HexFormat.of().formatHex(mac.doFinal(data));
  }

  public static String signedWebhookPayload(
      String secret, String webhookId, Instant timestamp, String body) {
    try {
      return new Webhook(secret).sign(webhookId, timestamp.getEpochSecond(), body);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static String negotiationApprovedPayload(String negotiationId) {
    return "{\"type\":\"negotiation.state.updated\","
        + "\"data\":{\"negotiationId\":\""
        + negotiationId
        + "\",\"fromState\":\"SUBMITTED\",\"toState\":\"IN_PROGRESS\",\"event\":\"APPROVE\"}}";
  }

  public static String negotiationStateUpdatedPayload(
      String negotiationId, String fromState, String toState, String event) {
    return "{\"type\":\"negotiation.state.updated\","
        + "\"data\":{\"negotiationId\":\""
        + negotiationId
        + "\",\"fromState\":\""
        + fromState
        + "\",\"toState\":\""
        + toState
        + "\",\"event\":\""
        + event
        + "\"}}";
  }

  public static String postAddedPayload(String negotiationId, String postId, long organizationId) {
    return "{\"type\":\"negotiation.post.added\","
        + "\"data\":{\"postId\":\""
        + postId
        + "\",\"negotiationId\":\""
        + negotiationId
        + "\",\"userId\":1,\"organizationId\":"
        + organizationId
        + "}}";
  }

  public static String resourceStateUpdatedPayload(
      String negotiationId, String resourceId, String fromState, String toState, String event) {
    return "{\"type\":\"negotiation.resource.state.updated\","
        + "\"data\":{\"negotiationId\":\""
        + negotiationId
        + "\",\"resourceId\":\""
        + resourceId
        + "\",\"fromState\":\""
        + fromState
        + "\",\"toState\":\""
        + toState
        + "\",\"event\":\""
        + event
        + "\"}}";
  }

  public static EntityModelNegotiationDTO negotiation(String id, String displayId, String status) {
    var n = new EntityModelNegotiationDTO();
    n.setId(id);
    n.setDisplayId(displayId);
    n.setStatus(status);
    return n;
  }

  public static EntityModelResourceWithStatusDTO resource(
      Long id, String sourceId, String name, String orgExtId, Long orgId) {
    var r = new EntityModelResourceWithStatusDTO();
    r.setId(id);
    r.setSourceId(sourceId);
    r.setName(name);
    r.setCurrentState(EntityModelResourceWithStatusDTO.CurrentStateEnum.SUBMITTED);
    var org = new OrganizationDTO();
    org.setId(orgId);
    org.setExternalId(orgExtId);
    r.setOrganization(org);
    return r;
  }

  public static EntityModelPostDTO post(
      String id, String text, EntityModelPostDTO.TypeEnum type, String orgId) {
    var p = new EntityModelPostDTO();
    p.setId(id);
    p.setText(text);
    p.setType(type);
    p.setOrganizationId(orgId);
    return p;
  }

  public static EntityModelPostDTO post(
      String id,
      String text,
      EntityModelPostDTO.TypeEnum type,
      String orgId,
      String createdByName,
      String createdByEmail) {
    var p = post(id, text, type, orgId);
    if (createdByName != null || createdByEmail != null) {
      var author = new UserResponseModel();
      author.setName(createdByName);
      author.setEmail(createdByEmail);
      p.setCreatedBy(author);
    }
    return p;
  }

  public static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  public static MockHttpServletRequest buildWebhookRequest(
      String webhookId, String signature, Instant timestamp) {
    var request = new MockHttpServletRequest();
    request.addHeader("Webhook-Id", webhookId);
    request.addHeader("Webhook-Timestamp", String.valueOf(timestamp.getEpochSecond()));
    request.addHeader("Webhook-Signature", signature);
    return request;
  }

  public static MockHttpServletRequest buildWebhookRequest(String webhookId, String signature) {
    return buildWebhookRequest(webhookId, signature, Instant.now());
  }

  public static OrganizationConfig organizationConfig(String orgExtId, TicketStrategy strategy) {
    return new OrganizationConfig(
        orgExtId,
        HelpDeskType.ZAMMAD,
        strategy,
        "https://zammad.example.com",
        Map.of("apiToken", "tok"),
        "secret",
        Map.of("group", "3"),
        new NegotiatorCredentials("test-client-id", "test-client-secret"));
  }
}
