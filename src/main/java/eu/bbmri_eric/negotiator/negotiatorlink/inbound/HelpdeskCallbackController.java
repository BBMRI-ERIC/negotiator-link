package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskCallbackAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.UnknownOrganizationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.WebhookAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HelpdeskCallbackController {

  private final OrganizationConfigRegistry registry;
  private final Map<HelpdeskType, HelpdeskCallbackAdapter> callbackAdapters;
  private final InboxService inboxService;

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

    String callbackId = adapter.extractCallbackId(headers).orElse(null);
    inboxService.capture(organizationExternalId, callbackId, body);
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
