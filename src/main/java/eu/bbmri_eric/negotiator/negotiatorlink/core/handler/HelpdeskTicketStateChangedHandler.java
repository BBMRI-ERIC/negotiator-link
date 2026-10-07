package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketStateChangedEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.TransitionResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Maps a help desk ticket-state change to a Negotiator resource lifecycle event and applies it to
 * every resource covered by the ticket. Unlike {@link HelpdeskResourceTransitionHandler}, an
 * invalid transition is silently dropped: no rejection comment is posted and no display field is
 * reset, because the callback originates from a native ticket-state change rather than an explicit
 * request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HelpdeskTicketStateChangedHandler
    implements HelpdeskCallbackHandler<TicketStateChangedEvent> {

  private final TicketMappingRepository ticketMappingRepository;
  private final ResourceTransitionService resourceTransitionService;

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.TICKET_STATE_CHANGED;
  }

  @Override
  public HandlerResult handle(TicketStateChangedEvent event, OrganizationConfig config) {
    String ticketId = event.ticketId();
    String newTicketState = event.newTicketState();

    String mappedEvent = config.ticketStateToResourceEvent().get(newTicketState);
    if (mappedEvent == null) {
      log.debug(
          "Ticket state '{}' on ticket '{}' has no configured resource event — discarding",
          newTicketState,
          ticketId);
      return HandlerResult.empty();
    }

    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, config.organizationExternalId());

    TransitionResult result = resourceTransitionService.apply(mappings, mappedEvent, config);

    String logMsg =
        result.rejection().isPresent()
            ? "Ticket state '{}' mapped to event '{}' but the transition is not allowed for ticket"
                + " '{}' — discarding"
            : "Ticket state '{}' applied event '{}' to resources of ticket '{}'";
    log.info(logMsg, newTicketState, mappedEvent, ticketId);
    return HandlerResult.of(result.intents());
  }
}
