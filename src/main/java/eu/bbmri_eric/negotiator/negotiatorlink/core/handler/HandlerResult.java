package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import java.util.List;
import java.util.Optional;

/** Return value of every handler */
public record HandlerResult(List<PendingIntent> intents, Optional<OriginatedPost> originatedPost) {

  private static final HandlerResult EMPTY = new HandlerResult(List.of(), Optional.empty());

  public static HandlerResult empty() {
    return EMPTY;
  }

  public static HandlerResult of(List<PendingIntent> intents) {
    return new HandlerResult(intents, Optional.empty());
  }

  public static HandlerResult of(List<PendingIntent> intents, OriginatedPost originatedPost) {
    return new HandlerResult(intents, Optional.of(originatedPost));
  }
}
