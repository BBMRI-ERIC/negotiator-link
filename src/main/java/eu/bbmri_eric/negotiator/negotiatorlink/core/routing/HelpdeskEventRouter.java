package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskCallbackHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionWithPostMessageEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches a verified {@link HelpdeskCallbackEvent} to the correct {@link
 * HelpdeskCallbackHandler} implementation(s). For {@link
 * HelpdeskCallbackType#RESOURCE_TRANSITION_WITH_POST_MESSAGE}, both handlers are invoked in order:
 * transition first, then post.
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

  /** Routes the event to the appropriate handler(s) based on its type. */
  public void route(HelpdeskCallbackEvent event, OrganizationConfig config) {
    switch (event.type()) {
      case RESOURCE_TRANSITION, POST_MESSAGE, TICKET_STATE_CHANGED ->
          dispatch(event.type(), event, config);
      case RESOURCE_TRANSITION_WITH_POST_MESSAGE -> {
        ResourceTransitionWithPostMessageEvent combined =
            (ResourceTransitionWithPostMessageEvent) event;
        dispatch(
            HelpdeskCallbackType.RESOURCE_TRANSITION,
            new ResourceTransitionEvent(
                combined.ticketId(), combined.resourceState(), combined.resourceTransitionEvent()),
            config);
        dispatch(
            HelpdeskCallbackType.POST_MESSAGE,
            new PostMessageEvent(combined.ticketId(), combined.postText(), combined.sender()),
            config);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends HelpdeskCallbackEvent> void dispatch(
      HelpdeskCallbackType type, T event, OrganizationConfig config) {
    HelpdeskCallbackHandler<T> handler = (HelpdeskCallbackHandler<T>) handlers.get(type);
    if (handler == null) {
      log.warn("No handler registered for helpdesk callback type '{}' — skipping", type);
      return;
    }
    log.debug(
        "Routing helpdesk callback type '{}' to {}", type, handler.getClass().getSimpleName());
    handler.handle(event, config);
  }
}
