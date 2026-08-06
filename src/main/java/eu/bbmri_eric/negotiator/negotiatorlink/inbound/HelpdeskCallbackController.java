package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
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

  public HelpdeskCallbackController(
      OrganizationConfigRegistry registry, Map<HelpDeskType, HelpDeskAdapter> adapters) {
    this.registry = registry;
    this.adapters = adapters;
  }

  @PostMapping("/webhooks/helpdesk/{organizationExternalId}")
  ResponseEntity<Void> receiveCallback(
      @PathVariable String organizationExternalId,
      HttpServletRequest request,
      @RequestBody byte[] body) {

    OrganizationConfig config =
        registry
            .findByExternalId(organizationExternalId)
            .orElseThrow(
                () -> {
                  log.warn(
                      "Helpdesk callback for unknown organization '{}'", organizationExternalId);
                  return new ResponseStatusException(NOT_FOUND);
                });

    HelpDeskAdapter adapter = adapters.get(config.type());
    if (adapter == null) {
      log.error("No adapter registered for help desk type '{}'", config.type());
      throw new ResponseStatusException(NOT_FOUND);
    }

    HttpHeaders headers = new HttpHeaders();
    request
        .getHeaderNames()
        .asIterator()
        .forEachRemaining(name -> headers.addAll(name, Collections.list(request.getHeaders(name))));

    if (!adapter.verifyCallback(headers, body, config.callbackSecret())) {
      log.warn(
          "Callback signature verification failed for organization '{}'", organizationExternalId);
      throw new ResponseStatusException(UNAUTHORIZED);
    }

    log.info("Received verified helpdesk callback for organization '{}'", organizationExternalId);
    return ResponseEntity.ok().build();
  }
}
