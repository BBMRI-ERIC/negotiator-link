package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.HelpdeskEventRouter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
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
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
public class HelpdeskCallbackController {

  private final OrganizationConfigRegistry registry;
  private final Map<HelpDeskType, HelpDeskAdapter> adapters;
  private final ObjectMapper objectMapper;
  private final HelpdeskEventRouter helpdeskEventRouter;

  private final Cache<String, Boolean> seenWebhookIds =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).build();

  public HelpdeskCallbackController(
      OrganizationConfigRegistry registry,
      Map<HelpDeskType, HelpDeskAdapter> adapters,
      ObjectMapper objectMapper,
      HelpdeskEventRouter helpdeskEventRouter) {
    this.registry = registry;
    this.adapters = adapters;
    this.objectMapper = objectMapper;
    this.helpdeskEventRouter = helpdeskEventRouter;
  }

  @PostMapping("/webhooks/helpdesk/{organizationExternalId}")
  ResponseEntity<Void> receiveCallback(
      @PathVariable String organizationExternalId,
      HttpServletRequest request,
      @RequestBody byte[] body) {

    OrganizationConfig config = getOrganizationConfig(organizationExternalId);
    HelpDeskAdapter adapter = adapters.get(config.type());
    if (adapter == null) {
      log.error("No adapter registered for help desk type '{}'", config.type());
      throw new ResponseStatusException(NOT_FOUND);
    }

    HttpHeaders headers = getHeaders(request);
    if (!adapter.verifyCallback(headers, body, config.callbackSecret())) {
      log.warn(
          "Callback signature verification failed for organization '{}'", organizationExternalId);
      throw new ResponseStatusException(UNAUTHORIZED);
    }

    String webhookId = adapter.extractCallbackId(headers).orElse(null);
    if (webhookId != null
        && seenWebhookIds
                .asMap()
                .putIfAbsent(organizationExternalId + ":" + webhookId, Boolean.TRUE)
            != null) {
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
            () -> {
              log.warn("Helpdesk callback for unknown organization '{}'", organizationExternalId);
              return new ResponseStatusException(NOT_FOUND);
            });
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
