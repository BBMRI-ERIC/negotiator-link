package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskCallbackHandler;
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
 * Dispatches a verified {@link HelpdeskCallbackEvent} to the correct {@link
 * HelpdeskCallbackHandler} implementation(s) and returns the outbound intents they decide.
 */
@Slf4j
@Service
public class HelpdeskEventRouter {

  private final Map<HelpdeskCallbackType, HelpdeskCallbackHandler<?>> handlers;

  public HelpdeskEventRouter(List<HelpdeskCallbackHandler<?>> handlers) {
    this.handlers =
        handlers.stream()
            .collect(
                Collectors.toMap(HelpdeskCallbackHandler::getSupportedType, Function.identity()));
  }

  /** Routes the event to the appropriate handler(s) based on its type, returning their intents. */
  public List<PendingIntent> route(HelpdeskCallbackEvent event, OrganizationConfig config) {
    return switch (event.type()) {
      case RESOURCE_TRANSITION, POST_MESSAGE, TICKET_STATE_CHANGED ->
          dispatch(event.type(), event, config);
      case RESOURCE_TRANSITION_WITH_POST_MESSAGE -> {
        ResourceTransitionWithPostMessageEvent combined =
            (ResourceTransitionWithPostMessageEvent) event;
        List<PendingIntent> intents = new ArrayList<>();
        intents.addAll(
            dispatch(
                HelpdeskCallbackType.RESOURCE_TRANSITION,
                new ResourceTransitionEvent(
                    combined.ticketId(),
                    combined.resourceState(),
                    combined.resourceTransitionEvent()),
                config));
        intents.addAll(
            dispatch(
                HelpdeskCallbackType.POST_MESSAGE,
                new PostMessageEvent(combined.ticketId(), combined.postText(), combined.sender()),
                config));
        yield intents;
      }
    };
  }

  @SuppressWarnings("unchecked")
  private <T extends HelpdeskCallbackEvent> List<PendingIntent> dispatch(
      HelpdeskCallbackType type, T event, OrganizationConfig config) {
    HelpdeskCallbackHandler<T> handler = (HelpdeskCallbackHandler<T>) handlers.get(type);
    if (handler == null) {
      log.warn("No handler registered for helpdesk callback type '{}' — skipping", type);
      return List.of();
    }
    log.debug(
        "Routing helpdesk callback type '{}' to {}", type, handler.getClass().getSimpleName());
    return handler.handle(event, config);
  }
}
