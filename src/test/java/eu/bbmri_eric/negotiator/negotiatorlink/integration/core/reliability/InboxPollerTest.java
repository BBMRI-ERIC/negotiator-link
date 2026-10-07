package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.job.OutboxClaimReaper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.EventSource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxPoller;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxPoller;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest
class InboxPollerTest {

  private static final String DELIVERY_ID = "delivery-1";
  private static final String PAYLOAD = "{}";
  private static final String ROUTING_FAILURE = "Negotiator unreachable";
  private static final int MAX_ATTEMPTS = 3;

  @Autowired private InboxPoller inboxPoller;
  @Autowired private InboxEventRepository inboxEventRepository;

  @MockitoBean private NegotiatorEventRouter negotiatorEventRouter;
  @MockitoBean private OutboxPoller outboxPoller;
  @MockitoBean private OutboxClaimReaper outboxClaimReaper;

  @BeforeEach
  void clear() {
    inboxEventRepository.deleteAll();
  }

  @Test
  void poll_transientRoutingFailureUnderCap_leavesRowReceivedWithBackoff() {
    InboxEvent event = saveReceived();
    when(negotiatorEventRouter.route(any()))
        .thenThrow(new ResourceAccessException(ROUTING_FAILURE));

    inboxPoller.poll();

    InboxEvent reloaded = reload(event);
    assertThat(reloaded.getStatus()).isEqualTo(InboxStatus.RECEIVED);
    assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    assertThat(reloaded.getNextAttemptAt()).isNotNull();
    assertThat(reloaded.getProcessedAt()).isNull();
  }

  @Test
  void poll_permanentRoutingFailure_marksRowFailed() {
    InboxEvent event = saveReceived();
    when(negotiatorEventRouter.route(any()))
        .thenThrow(
            new RestClientResponseException(
                ROUTING_FAILURE, HttpStatus.BAD_REQUEST, ROUTING_FAILURE, null, null, null));

    inboxPoller.poll();

    InboxEvent reloaded = reload(event);
    assertThat(reloaded.getStatus()).isEqualTo(InboxStatus.FAILED);
    assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    assertThat(reloaded.getLastError()).isNotNull();
    assertThat(reloaded.getProcessedAt()).isNotNull();
  }

  @Test
  void poll_transientRoutingFailureAtCap_marksRowFailed() {
    InboxEvent event = saveReceived(MAX_ATTEMPTS - 1);
    when(negotiatorEventRouter.route(any()))
        .thenThrow(new ResourceAccessException(ROUTING_FAILURE));

    inboxPoller.poll();

    InboxEvent reloaded = reload(event);
    assertThat(reloaded.getStatus()).isEqualTo(InboxStatus.FAILED);
    assertThat(reloaded.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
    assertThat(reloaded.getLastError()).isNotNull();
    assertThat(reloaded.getProcessedAt()).isNotNull();
  }

  private InboxEvent saveReceived() {
    return saveReceived(0);
  }

  private InboxEvent saveReceived(int attemptCount) {
    InboxEvent event = new InboxEvent(EventSource.NEGOTIATOR.name(), DELIVERY_ID, null, PAYLOAD);
    event.setAttemptCount(attemptCount);
    return inboxEventRepository.save(event);
  }

  private InboxEvent reload(InboxEvent event) {
    return inboxEventRepository.findById(event.getRowId()).orElseThrow();
  }
}
