package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationCache;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingTicketCreationCacheTest {

  private static final String NEGOTIATION_ID = "neg-1";

  @Mock private OutboxIntentRepository outboxIntentRepository;

  private PendingTicketCreationCache cache;

  @BeforeEach
  void setUp() {
    cache = new PendingTicketCreationCache(outboxIntentRepository);
  }

  @Test
  void isTracked_afterTrack_isTrue() {
    cache.track(NEGOTIATION_ID);

    assertThat(cache.isTracked(NEGOTIATION_ID)).isTrue();
  }

  @Test
  void isTracked_cacheMissAndOutboxHasPending_isTrue() {
    when(outboxIntentRepository.countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL))
        .thenReturn(1L);

    assertThat(cache.isTracked(NEGOTIATION_ID)).isTrue();
  }

  @Test
  void isTracked_cacheMissAndOutboxHasPending_rewarmsCache() {
    when(outboxIntentRepository.countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL))
        .thenReturn(1L);

    assertThat(cache.isTracked(NEGOTIATION_ID)).isTrue();

    // After re-warming, a subsequent lookup is served from the cache without hitting the outbox.
    assertThat(cache.isTracked(NEGOTIATION_ID)).isTrue();
    verify(outboxIntentRepository, times(1))
        .countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL);
  }

  @Test
  void isTracked_cacheMissAndOutboxHasNone_isFalse() {
    when(outboxIntentRepository.countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL))
        .thenReturn(0L);

    assertThat(cache.isTracked(NEGOTIATION_ID)).isFalse();
  }

  @Test
  void removeIfNoPendingCreateTicket_noneRemaining_clearsEntry() {
    cache.track(NEGOTIATION_ID);
    when(outboxIntentRepository.countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL))
        .thenReturn(0L);

    cache.removeIfNoPendingCreateTicket(NEGOTIATION_ID);

    assertThat(cache.isTracked(NEGOTIATION_ID)).isFalse();
  }

  @Test
  void removeIfNoPendingCreateTicket_otherTicketStillPending_keepsEntryTracked() {
    cache.track(NEGOTIATION_ID);
    when(outboxIntentRepository.countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL))
        .thenReturn(1L);

    cache.removeIfNoPendingCreateTicket(NEGOTIATION_ID);

    assertThat(cache.isTracked(NEGOTIATION_ID)).isTrue();
  }

  @Test
  void warmFromOutbox_tracksNonterminalCreateTicketNegotiations() {
    when(outboxIntentRepository.findNegotiationIdsWithPendingCreateTicket(
            OutboxStatus.NON_TERMINAL))
        .thenReturn(List.of(NEGOTIATION_ID, "neg-2"));

    cache.warmFromOutbox();

    assertThat(cache.isTracked(NEGOTIATION_ID)).isTrue();
    assertThat(cache.isTracked("neg-2")).isTrue();
  }

  @Test
  void warmFromOutbox_noPending_tracksNothing() {
    when(outboxIntentRepository.findNegotiationIdsWithPendingCreateTicket(
            OutboxStatus.NON_TERMINAL))
        .thenReturn(List.of());
    when(outboxIntentRepository.countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL))
        .thenReturn(0L);

    cache.warmFromOutbox();

    assertThat(cache.isTracked(NEGOTIATION_ID)).isFalse();
    verify(outboxIntentRepository)
        .countPendingCreateTicket(NEGOTIATION_ID, OutboxStatus.NON_TERMINAL);
  }
}
