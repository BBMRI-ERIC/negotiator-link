package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedPostRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class OriginatedPostRepositoryTest {

  @Autowired private OriginatedPostRepository repository;
  @Autowired private TestEntityManager em;

  @Test
  void findFirstByTextHashAndOrganizationId_whenMultipleMatches_returnsOldest() {
    var older = new OriginatedPost("hash-1", "org-1", "ticket-1");
    var newer = new OriginatedPost("hash-1", "org-1", "ticket-2");
    repository.save(older);
    repository.save(newer);
    em.flush();
    var result =
        repository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc("hash-1", "org-1");
    assertThat(result).isPresent();
    assertThat(result.get().getTicketId()).isEqualTo(older.getTicketId());
  }

  @Test
  void findFirstByTextHashAndOrganizationId_whenNoMatch_returnsEmpty() {
    assertThat(
            repository.findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc("hash-x", "org-1"))
        .isEmpty();
  }

  @Test
  void deleteByCreatedAtBefore_whenRowIsStale_removesIt() {
    var stale = new OriginatedPost("hash-1", "org-1", "ticket-1");
    repository.save(stale);
    em.flush();
    repository.deleteByCreatedAtBefore(Instant.now().plus(1, ChronoUnit.HOURS));
    assertThat(repository.findAll()).isEmpty();
  }

  @Test
  void deleteByCreatedAtBefore_whenRowIsFresh_keepsIt() {
    repository.save(new OriginatedPost("hash-1", "org-1", "ticket-1"));
    em.flush();
    repository.deleteByCreatedAtBefore(Instant.now().minus(1, ChronoUnit.HOURS));
    assertThat(repository.findAll()).hasSize(1);
  }
}
