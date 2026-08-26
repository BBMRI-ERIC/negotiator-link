package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskCallbackAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.HelpdeskEventRouter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.ReplayGuard;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.UnknownOrganizationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.WebhookAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class HelpdeskCallbackController {

  private final OrganizationConfigRegistry registry;
  private final Map<HelpdeskType, HelpdeskCallbackAdapter> callbackAdapters;
  private final ObjectMapper objectMapper;
  private final HelpdeskEventRouter helpdeskEventRouter;
  private final ReplayGuard replayGuard;

  public HelpdeskCallbackController(
      OrganizationConfigRegistry registry,
      Map<HelpdeskType, HelpdeskCallbackAdapter> callbackAdapters,
      ObjectMapper objectMapper,
      HelpdeskEventRouter helpdeskEventRouter,
      ReplayGuard replayGuard) {
    this.registry = registry;
    this.callbackAdapters = callbackAdapters;
    this.objectMapper = objectMapper;
    this.helpdeskEventRouter = helpdeskEventRouter;
    this.replayGuard = replayGuard;
  }

  @PostMapping("/webhooks/helpdesk/{organizationExternalId}")
  ResponseEntity<Void> receiveCallback(
      @PathVariable String organizationExternalId,
      HttpServletRequest request,
      @RequestBody byte[] body) {

    OrganizationConfig config = getOrganizationConfig(organizationExternalId);
    HelpdeskCallbackAdapter adapter = callbackAdapters.get(config.type());
    if (adapter == null) {
      throw new UnknownOrganizationException(
          "No adapter registered for help desk type " + config.type());
    }

    HttpHeaders headers = getHeaders(request);
    if (!adapter.verifyCallback(headers, body, config.callbackSecret())) {
      throw new WebhookAuthenticationException(
          "Callback signature verification failed for organization " + organizationExternalId);
    }

    String webhookId = adapter.extractCallbackId(headers).orElse(null);
    if (webhookId != null && replayGuard.alreadySeen(organizationExternalId + ":" + webhookId)) {
      log.debug(
          "Duplicate helpdesk webhook '{}' for organization '{}' — acknowledged and discarded",
          webhookId,
          organizationExternalId);
      return ResponseEntity.ok().build();
    }

    log.debug("Received verified helpdesk callback for organization '{}'", organizationExternalId);

    Map<String, Object> payload;
    try {
      payload = objectMapper.readValue(body, new TypeReference<>() {});
    } catch (IOException e) {
      log.warn(
          "Failed to parse helpdesk callback body for organization '{}'",
          organizationExternalId,
          e);
      return ResponseEntity.ok().build();
    }

    Optional<HelpdeskCallbackEvent> eventOpt = adapter.parseCallback(payload);
    if (eventOpt.isEmpty()) {
      log.warn(
          "Adapter could not parse callback for organization '{}' — discarding",
          organizationExternalId);
      return ResponseEntity.ok().build();
    }

    helpdeskEventRouter.route(eventOpt.get(), config);
    return ResponseEntity.ok().build();
  }

  private OrganizationConfig getOrganizationConfig(String organizationExternalId) {
    return registry
        .findByExternalId(organizationExternalId)
        .orElseThrow(
            () ->
                new UnknownOrganizationException("Unknown organization " + organizationExternalId));
  }

  private HttpHeaders getHeaders(HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    request
        .getHeaderNames()
        .asIterator()
        .forEachRemaining(name -> headers.addAll(name, Collections.list(request.getHeaders(name))));
    return headers;
  }
}
