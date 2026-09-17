package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies the organization-configured mapping from a Negotiator state (negotiation or resource) to
 * a platform-native ticket state. A failed help desk call is logged and swallowed so it never
 * aborts the surrounding event processing.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TicketStateUpdater {

  /**
   * Resolves {@code sourceState} against {@code stateToTicketState} and, if a mapping exists,
   * updates the ticket's platform-native state via the adapter. Does nothing when the state is
   * unmapped.
   *
   * @param logFor human-readable label for the source state ("negotiation" or "resource"), used
   *     only for logging
   */
  public static void applyTicketStateMapping(
      HelpdeskClient client,
      OrganizationConfig config,
      String ticketId,
      Map<String, String> stateToTicketState,
      String sourceState,
      String logFor) {
    String ticketState = stateToTicketState.get(sourceState);
    if (ticketState == null) {
      return;
    }
    try {
      client.updateTicketState(ticketId, ticketState, config);
      log.info(
          "Set ticket '{}' state to '{}' for {} state '{}'",
          ticketId,
          ticketState,
          logFor,
          sourceState);
    } catch (Exception e) {
      log.error("Failed to update ticket state on ticket '{}'", ticketId, e);
    }
  }
}
