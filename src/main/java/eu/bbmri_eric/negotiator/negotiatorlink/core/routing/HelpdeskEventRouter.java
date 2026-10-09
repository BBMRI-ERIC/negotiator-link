package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HandlerResult;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskEventHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionWithPostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches a verified {@link HelpdeskCallbackEvent} to the correct {@link HelpdeskEventHandler}
 * implementation(s) and returns the outbound intents they decide.
 */
@Slf4j
@Service
public class HelpdeskEventRouter {

  private final Map<HelpdeskCallbackType, HelpdeskEventHandler<?>> handlers;

  public HelpdeskEventRouter(List<HelpdeskEventHandler<?>> handlers) {
    this.handlers =
        handlers.stream()
            .collect(Collectors.toMap(HelpdeskEventHandler::getSupportedType, Function.identity()));
  }

  /** Routes the event to the appropriate handler(s) based on its type, returning their result. */
  public HandlerResult route(HelpdeskCallbackEvent event, OrganizationConfig config) {
    return switch (event.type()) {
      case RESOURCE_TRANSITION, POST_MESSAGE, TICKET_STATE_CHANGED ->
          dispatch(event.type(), event, config);
      case RESOURCE_TRANSITION_WITH_POST_MESSAGE -> {
        ResourceTransitionWithPostMessageEvent combined =
            (ResourceTransitionWithPostMessageEvent) event;
        HandlerResult transition =
            dispatch(
                HelpdeskCallbackType.RESOURCE_TRANSITION,
                new ResourceTransitionEvent(
                    combined.ticketId(),
                    combined.resourceState(),
                    combined.resourceTransitionEvent()),
                config);
        HandlerResult post =
            dispatch(
                HelpdeskCallbackType.POST_MESSAGE,
                new PostMessageEvent(combined.ticketId(), combined.postText(), combined.sender()),
                config);
        List<PendingIntent> intents = new ArrayList<>();
        intents.addAll(transition.intents());
        intents.addAll(post.intents());
        // only POST_MESSAGE produces an originated post
        yield new HandlerResult(intents, post.originatedPost());
      }
    };
  }

  @SuppressWarnings("unchecked")
  private <T extends HelpdeskCallbackEvent> HandlerResult dispatch(
      HelpdeskCallbackType type, T event, OrganizationConfig config) {
    HelpdeskEventHandler<T> handler = (HelpdeskEventHandler<T>) handlers.get(type);
    if (handler == null) {
      log.warn("No handler registered for helpdesk callback type '{}' — skipping", type);
      return HandlerResult.empty();
    }
    log.debug(
        "Routing helpdesk callback type '{}' to {}", type, handler.getClass().getSimpleName());
    return handler.handle(event, config);
  }
}
