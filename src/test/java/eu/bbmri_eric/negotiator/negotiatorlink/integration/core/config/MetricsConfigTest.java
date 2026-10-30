package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.EventSource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.integration.util.IntegrationBase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationBase
class MetricsConfigTest {

  @Autowired private MeterRegistry meterRegistry;
  @Autowired private InboxEventRepository inboxEventRepository;
  @Autowired private OutboxIntentRepository outboxIntentRepository;

  @BeforeEach
  void clear() {
    inboxEventRepository.deleteAll();
    outboxIntentRepository.deleteAll();
  }

  @Test
  void depthGauges_areRegistered() {
    assertThat(gauge("outbox_queue_depth")).isNotNull();
    assertThat(gauge("outbox_dead_letter_depth")).isNotNull();
    assertThat(gauge("inbox_queue_depth")).isNotNull();
    assertThat(gauge("inbox_dead_letter_depth")).isNotNull();
  }

  @Test
  void outboxQueueDepth_countsPendingIntents() {
    savePendingIntent();
    savePendingIntent();

    assertThat(gauge("outbox_queue_depth").value()).isEqualTo(2.0);
  }

  @Test
  void outboxDeadLetterDepth_countsDeadLetterAndFailedPermanent() {
    saveIntent(OutboxStatus.DEAD_LETTER);
    saveIntent(OutboxStatus.FAILED_PERMANENT);
    savePendingIntent();

    assertThat(gauge("outbox_dead_letter_depth").value()).isEqualTo(2.0);
  }

  @Test
  void inboxDeadLetterDepth_countsFailedEvents() {
    saveInbox(InboxStatus.FAILED, 1);
    saveInbox(InboxStatus.RECEIVED, 0);

    assertThat(gauge("inbox_dead_letter_depth").value()).isEqualTo(1.0);
  }

  private Gauge gauge(String name) {
    return meterRegistry.find(name).gauge();
  }

  private void savePendingIntent() {
    saveIntent(OutboxStatus.PENDING);
  }

  private void saveIntent(OutboxStatus status) {
    InboxEvent parent =
        inboxEventRepository.save(
            new InboxEvent("someOrgExternalId", "d-" + System.nanoTime(), null, "{}"));
    OutboxIntent intent =
        new OutboxIntent(
            parent.getRowId(),
            OutboxIntentType.ADD_COMMENT,
            TargetKind.TICKET.name(),
            "ticket-1",
            "neg-1",
            "bbmri:DE:MHH",
            "{}");
    intent.setStatus(status);
    outboxIntentRepository.save(intent);
  }

  private void saveInbox(InboxStatus status, int attemptCount) {
    InboxEvent event =
        new InboxEvent(EventSource.NEGOTIATOR.name(), "d-" + System.nanoTime(), null, "{}");
    event.setStatus(status);
    event.setAttemptCount(attemptCount);
    inboxEventRepository.save(event);
  }
}
