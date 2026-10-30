package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.integration.util.IntegrationBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@IntegrationBase
class OutboxWorkerMetricsTest {

  @Autowired private MeterRegistry meterRegistry;

  @Value("${negotiator-link.outbox.worker-count}")
  private int workerCount;

  @Test
  void executorMetrics_boundForEachWorker() {
    for (int i = 0; i < workerCount; i++) {
      String worker = "outbox-worker-" + i;
      assertThat(meterRegistry.find("executor.queued").tag("name", worker).gauge())
          .as("executor.queued for %s", worker)
          .isNotNull();
      assertThat(meterRegistry.find("executor.active").tag("name", worker).gauge())
          .as("executor.active for %s", worker)
          .isNotNull();
      assertThat(meterRegistry.find("executor.completed").tag("name", worker).functionCounter())
          .as("executor.completed for %s", worker)
          .isNotNull();
    }
  }
}
