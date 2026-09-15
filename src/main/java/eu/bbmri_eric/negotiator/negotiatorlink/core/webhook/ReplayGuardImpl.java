package eu.bbmri_eric.negotiator.negotiatorlink.core.webhook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** In-memory {@link ReplayGuard} backed by a Caffeine cache with a 15-minute expiry. */
@Component
public class ReplayGuardImpl implements ReplayGuard {

  private final Cache<String, Boolean> seen =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).build();

  @Override
  public boolean alreadySeen(String key) {
    return seen.asMap().putIfAbsent(key, Boolean.TRUE) != null;
  }
}
