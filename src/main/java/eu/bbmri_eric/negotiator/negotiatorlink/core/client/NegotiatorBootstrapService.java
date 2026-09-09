package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NegotiatorBootstrapService implements ApplicationRunner {

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry registry;

  @Override
  public void run(ApplicationArguments args) {
    for (OrganizationConfig config : registry.findAll()) {
      try {
        var userInfo = negotiatorClient.getUserInfo(config);
        log.info(
            "Verified Negotiator account for org '{}': subject='{}'",
            config.organizationExternalId(),
            userInfo != null ? userInfo.getId() : "unknown");
      } catch (Exception e) {
        log.warn(
            "Could not verify Negotiator account for org '{}' — the Person record may not exist yet: {}",
            config.organizationExternalId(),
            e.getMessage());
      }
    }
  }
}
