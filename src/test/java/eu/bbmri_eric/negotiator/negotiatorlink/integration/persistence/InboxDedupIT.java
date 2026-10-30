package eu.bbmri_eric.negotiator.negotiatorlink.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.EventSource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.integration.util.IntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@IntegrationBase
class InboxDedupIT {

  private static final String SOURCE = EventSource.NEGOTIATOR.name();
  private static final String DELIVERY_ID = "delivery-dup-1";

  @Autowired private InboxEventRepository inboxEventRepository;

  @Test
  void duplicateSourceAndDeliveryId_isRejectedByUniqueConstraint() {
    inboxEventRepository.saveAndFlush(event());

    assertThatThrownBy(() -> inboxEventRepository.saveAndFlush(event()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void sameDeliveryIdDifferentSource_isAllowed() {
    inboxEventRepository.saveAndFlush(
        new InboxEvent(EventSource.NEGOTIATOR.name(), DELIVERY_ID, "TEST", "{}"));
    inboxEventRepository.saveAndFlush(new InboxEvent("bbmri:DE:MHH", DELIVERY_ID, "TEST", "{}"));

    assertThat(inboxEventRepository.count()).isEqualTo(2);
  }

  private static InboxEvent event() {
    return new InboxEvent(SOURCE, DELIVERY_ID, "TEST", "{}");
  }
}
