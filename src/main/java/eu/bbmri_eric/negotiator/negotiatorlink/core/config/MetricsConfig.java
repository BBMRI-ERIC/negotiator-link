package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;

/** Registers the pipeline depth gauges. */
@org.springframework.context.annotation.Configuration
public class MetricsConfig {

  private final OutboxIntentRepository outboxRepo;
  private final InboxEventRepository inboxRepo;
  private final MeterRegistry registry;

  public MetricsConfig(
      OutboxIntentRepository outboxRepo, InboxEventRepository inboxRepo, MeterRegistry registry) {
    this.outboxRepo = outboxRepo;
    this.inboxRepo = inboxRepo;
    this.registry = registry;
  }

  @PostConstruct
  void registerDepthGauges() {
    Gauge.builder(
            "outbox_queue_depth", outboxRepo, r -> r.countByStatusIn(List.of(OutboxStatus.PENDING)))
        .description("Outbound intents waiting to be delivered (PENDING)")
        .register(registry);
    Gauge.builder(
            "outbox_dead_letter_depth",
            outboxRepo,
            r ->
                r.countByStatusIn(List.of(OutboxStatus.DEAD_LETTER, OutboxStatus.FAILED_PERMANENT)))
        .description("Outbound intents parked in a terminal failure state")
        .register(registry);
    Gauge.builder("inbox_queue_depth", inboxRepo, r -> r.countByStatus(InboxStatus.RECEIVED))
        .description("Inbox events waiting to be processed (RECEIVED)")
        .register(registry);
    Gauge.builder("inbox_dead_letter_depth", inboxRepo, r -> r.countByStatus(InboxStatus.FAILED))
        .description("Inbound events parked in the terminal FAILED state")
        .register(registry);
  }
}
