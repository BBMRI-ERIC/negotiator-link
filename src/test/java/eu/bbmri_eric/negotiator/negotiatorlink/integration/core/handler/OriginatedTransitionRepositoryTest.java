package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedTransitionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class OriginatedTransitionRepositoryTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String HELP_DESK_TICKET_ID = "ticket-1";

  @Autowired private OriginatedTransitionRepository repository;
  @Autowired private TestEntityManager em;

  @Test
  void findByNegotiationIdAndTicketId_whenExists_returnsRow() {
    repository.save(new OriginatedTransition(NEGOTIATION_ID, HELP_DESK_TICKET_ID, 3, "APPROVED"));
    em.flush();
    var result = repository.findByNegotiationIdAndTicketId(NEGOTIATION_ID, HELP_DESK_TICKET_ID);
    assertThat(result).isPresent();
    assertThat(result.get().getExpectedCount()).isEqualTo(3);
    assertThat(result.get().getNewState()).isEqualTo("APPROVED");
    assertThat(result.get().getReceivedCount()).isZero();
  }

  @Test
  void findByNegotiationIdAndTicketId_whenUnknown_returnsEmpty() {
    assertThat(repository.findByNegotiationIdAndTicketId("neg-x", "ticket-x")).isEmpty();
  }

  @Test
  void deleteByCreatedAtBefore_whenRowIsStale_removesIt() {
    repository.save(new OriginatedTransition(NEGOTIATION_ID, HELP_DESK_TICKET_ID, 3, "APPROVED"));
    em.flush();
    repository.deleteByCreatedAtBefore(Instant.now().plus(1, ChronoUnit.HOURS));
    assertThat(repository.findAll()).isEmpty();
  }

  @Test
  void deleteByCreatedAtBefore_whenRowIsFresh_keepsIt() {
    repository.save(new OriginatedTransition(NEGOTIATION_ID, HELP_DESK_TICKET_ID, 3, "APPROVED"));
    em.flush();
    repository.deleteByCreatedAtBefore(Instant.now().minus(1, ChronoUnit.HOURS));
    assertThat(repository.findAll()).hasSize(1);
  }
}
