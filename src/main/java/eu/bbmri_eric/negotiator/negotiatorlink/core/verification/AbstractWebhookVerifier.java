package eu.bbmri_eric.negotiator.negotiatorlink.core.verification;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

public abstract class AbstractWebhookVerifier implements WebhookVerifier {

  private final Cache<String, Boolean> seenIds =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).build();

  @Override
  public boolean checkAndMarkSeen(String webhookId) {
    return seenIds.asMap().putIfAbsent(webhookId, Boolean.TRUE) != null;
  }
}
