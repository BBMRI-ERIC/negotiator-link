package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class AfterCommitMetrics {

  private final MeterRegistry meterRegistry;

  public void incrementAfterCommit(String name, String... tags) {
    afterCommit(() -> meterRegistry.counter(name, tags).increment());
  }

  private void afterCommit(Runnable increment) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              increment.run();
            }
          });
    } else {
      increment.run();
    }
  }
}
