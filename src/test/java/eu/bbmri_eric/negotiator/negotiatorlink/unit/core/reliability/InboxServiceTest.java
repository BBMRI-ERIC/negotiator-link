package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

  @Mock private InboxEventRepository inboxEventRepository;
  @Mock private InboxService self;

  private SimpleMeterRegistry meterRegistry;
  private InboxService inboxService;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    inboxService = new InboxService(inboxEventRepository, self, meterRegistry);
  }

  @Test
  void capture_returnsEvent_whenInsertSucceeds() {
    InboxEvent event = new InboxEvent("NEGOTIATOR", "delivery-1", null, "{}");
    when(self.insert("NEGOTIATOR", "delivery-1", "{}")).thenReturn(event);

    Optional<InboxEvent> result = inboxService.capture("NEGOTIATOR", "delivery-1", "{}".getBytes());

    assertThat(result).isPresent().contains(event);
    assertThat(meterRegistry.counter("inbox_events_captured_total", "source", "NEGOTIATOR").count())
        .isEqualTo(1.0);
  }

  @Test
  void capture_returnsEmpty_whenDeliveryIsDuplicate() {
    InboxEvent existing = new InboxEvent("NEGOTIATOR", "delivery-1", null, "{}");
    when(self.insert(any(), any(), any())).thenThrow(new DataIntegrityViolationException("dup"));
    when(inboxEventRepository.findBySourceAndDeliveryId("NEGOTIATOR", "delivery-1"))
        .thenReturn(Optional.of(existing));

    Optional<InboxEvent> result = inboxService.capture("NEGOTIATOR", "delivery-1", "{}".getBytes());

    assertThat(result).isEmpty();
    assertThat(meterRegistry.find("inbox_events_captured_total").counter()).isNull();
  }

  @Test
  void capture_rethrows_whenDataIntegrityViolationIsNotADuplicate() {
    when(self.insert(any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("oversized"));
    when(inboxEventRepository.findBySourceAndDeliveryId("NEGOTIATOR", "delivery-1"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> inboxService.capture("NEGOTIATOR", "delivery-1", "{}".getBytes()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void capture_usesContentHashAsDeliveryId_whenDeliveryIdIsBlank() {
    InboxEvent event = new InboxEvent("NEGOTIATOR", "sha256:abc", null, "{}");
    when(self.insert(any(), any(), any())).thenReturn(event);

    inboxService.capture("NEGOTIATOR", "", "{}".getBytes());

    verify(self).insert(any(), org.mockito.ArgumentMatchers.startsWith("sha256:"), any());
    verify(inboxEventRepository, never()).findBySourceAndDeliveryId(any(), any());
  }
}
