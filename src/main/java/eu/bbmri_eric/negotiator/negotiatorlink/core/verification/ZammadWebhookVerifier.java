package eu.bbmri_eric.negotiator.negotiatorlink.core.verification;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service("zammadWebhookVerifier")
public class ZammadWebhookVerifier extends AbstractWebhookVerifier {

  @Override
  public void verify(HttpServletRequest request, byte[] body) {
    // TODO: verify X-Helpdesk-Secret header against callbackSecret from OrganizationConfig
    throw new UnsupportedOperationException("ZammadWebhookVerifier.verify not yet implemented");
  }
}
