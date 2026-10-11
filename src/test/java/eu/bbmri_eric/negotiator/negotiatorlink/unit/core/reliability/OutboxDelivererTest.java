package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.DispatchResult;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.FailureClass;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.FailureClassifier;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxDeliverer;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentDispatcher;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingTicketCreationCache;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OutboxDelivererTest {

  private static final long ROW_ID = 7L;
  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-7";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final int MAX_ATTEMPTS = 4;
  private static final UUID CLAIM_TOKEN = UUID.randomUUID();

  @Mock private OutboxIntentRepository outboxIntentRepository;
  @Mock private OutboxIntentDispatcher dispatcher;
  @Mock private OutboxService outboxService;
  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private AttachmentForwardingService attachmentForwardingService;
  @Mock private FailureClassifier failureClassifier;
  @Mock private PendingTicketCreationCache pendingTicketCreationCache;

  private OutboxDeliverer deliverer;

  @BeforeEach
  void setUp() {
    NegotiatorLinkProperties properties = new NegotiatorLinkProperties();
    NegotiatorLinkProperties.OutboxProperties outbox = properties.getOutbox();
    outbox.setMaxAttempts(MAX_ATTEMPTS);
    outbox.setBaseBackoff(Duration.ofSeconds(1));
    outbox.setMaxBackoff(Duration.ofSeconds(30));
    deliverer =
        new OutboxDeliverer(
            outboxIntentRepository,
            dispatcher,
            outboxService,
            ticketMappingRepository,
            attachmentForwardingService,
            failureClassifier,
            pendingTicketCreationCache,
            properties);
  }

  @Test
  void dispatch_claimedIntent_returnsDispatchResult() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    when(dispatcher.dispatch(intent)).thenReturn(DispatchResult.empty());

    DispatchResult result = deliverer.dispatch(ROW_ID, CLAIM_TOKEN);

    assertEquals(DispatchResult.empty(), result);
    verify(dispatcher).dispatch(intent);
  }

  @Test
  void dispatch_rowNotInProgress_returnsNullAndDoesNotDispatch() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.PENDING);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));

    assertNull(deliverer.dispatch(ROW_ID, CLAIM_TOKEN));
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void dispatch_tokenMismatch_returnsNullAndDoesNotDispatch() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    intent.setClaimToken(UUID.randomUUID()); // different token — simulates reclaim by reaper
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));

    assertNull(deliverer.dispatch(ROW_ID, CLAIM_TOKEN));
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void dispatch_supersededStateIntent_returnsEmptyWithoutDispatching() {
    OutboxIntent older = intent(OutboxIntentType.SET_TICKET_STATE, OutboxStatus.IN_PROCESS);
    older.setRowId(ROW_ID);
    older.setInboxRowId(1L);
    OutboxIntent newer = intent(OutboxIntentType.SET_TICKET_STATE, OutboxStatus.IN_PROCESS);
    newer.setRowId(ROW_ID + 1);
    newer.setInboxRowId(2L);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(older));
    when(outboxIntentRepository
            .findFirstByIntentTypeAndOrganizationExtIdAndTargetIdAndStatusInOrderByInboxRowIdDesc(
                eq(OutboxIntentType.SET_TICKET_STATE),
                eq(ORG_EXT_ID),
                eq(TICKET_ID),
                eq(
                    List.of(
                        OutboxStatus.PENDING, OutboxStatus.IN_PROCESS, OutboxStatus.DELIVERED))))
        .thenReturn(Optional.of(newer));

    DispatchResult result = deliverer.dispatch(ROW_ID, CLAIM_TOKEN);

    assertEquals(DispatchResult.empty(), result);
    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void commit_persistsSideEffectsAndMarksDelivered() {
    OutboxIntent intent = intent(OutboxIntentType.CREATE_TICKET, OutboxStatus.IN_PROCESS);
    intent.setClaimExpiresAt(Instant.now().plusSeconds(30));
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    TicketMapping mapping = ticketMapping();
    DispatchResult result =
        new DispatchResult(
            List.of(mapping),
            Optional.of(
                new DispatchResult.ForwardedAttachmentsRecord(
                    NEGOTIATION_ID, ORG_EXT_ID, TICKET_ID, List.of("att-1"))));

    TransactionSynchronizationManager.initSynchronization();
    try {
      deliverer.commit(ROW_ID, CLAIM_TOKEN, result);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(ticketMappingRepository).saveAll(List.of(mapping));
    verify(attachmentForwardingService)
        .recordForwardedAttachments(NEGOTIATION_ID, ORG_EXT_ID, TICKET_ID, List.of("att-1"));
    assertEquals(OutboxStatus.DELIVERED, intent.getStatus());
    assertNull(intent.getClaimExpiresAt());
    assertNull(intent.getClaimToken());
    verify(outboxIntentRepository).save(intent);
  }

  @Test
  void commit_enqueuesFollowUpIntentsAgainstOriginatingInboxRow() {
    OutboxIntent intent = intent(OutboxIntentType.CREATE_TICKET, OutboxStatus.IN_PROCESS);
    intent.setInboxRowId(99L);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    PendingIntent child =
        new PendingIntent(
            OutboxIntentType.ADD_COMMENT,
            TargetKind.TICKET,
            TICKET_ID,
            NEGOTIATION_ID,
            ORG_EXT_ID,
            "{\"text\":\"backlog\",\"commentType\":\"PLAIN_TEXT\"}");
    DispatchResult result =
        new DispatchResult(List.of(ticketMapping()), Optional.empty(), List.of(child));

    TransactionSynchronizationManager.initSynchronization();
    try {
      deliverer.commit(ROW_ID, CLAIM_TOKEN, result);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(outboxService).enqueue(List.of(child), 99L);
    assertEquals(OutboxStatus.DELIVERED, intent.getStatus());
  }

  @Test
  void commit_tokenMismatch_doesNothing() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    intent.setClaimToken(UUID.randomUUID()); // different token — simulates reclaim by reaper
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    DispatchResult result = new DispatchResult(List.of(), Optional.empty());

    TransactionSynchronizationManager.initSynchronization();
    try {
      deliverer.commit(ROW_ID, CLAIM_TOKEN, result);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(ticketMappingRepository, never()).saveAll(any());
    assertEquals(OutboxStatus.IN_PROCESS, intent.getStatus());
    verify(outboxIntentRepository, never()).save(any());
  }

  @Test
  void commit_rowReclaimedToPendingWithClearedToken_discardsResult() {
    OutboxIntent intent = intent(OutboxIntentType.CREATE_TICKET, OutboxStatus.PENDING);
    intent.setClaimToken(null);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    DispatchResult result = new DispatchResult(List.of(ticketMapping()), Optional.empty());

    TransactionSynchronizationManager.initSynchronization();
    try {
      deliverer.commit(ROW_ID, CLAIM_TOKEN, result);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(ticketMappingRepository, never()).saveAll(any());
    assertEquals(OutboxStatus.PENDING, intent.getStatus());
    verify(outboxIntentRepository, never()).save(any());
  }

  @Test
  void commit_inProgressWithNullToken_discardsResult() {
    OutboxIntent intent = intent(OutboxIntentType.CREATE_TICKET, OutboxStatus.IN_PROCESS);
    intent.setClaimToken(null);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    DispatchResult result = new DispatchResult(List.of(ticketMapping()), Optional.empty());

    TransactionSynchronizationManager.initSynchronization();
    try {
      deliverer.commit(ROW_ID, CLAIM_TOKEN, result);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(ticketMappingRepository, never()).saveAll(any());
    assertEquals(OutboxStatus.IN_PROCESS, intent.getStatus());
    verify(outboxIntentRepository, never()).save(any());
  }

  @Test
  void commit_createTicket_removesCacheAfterCommit() {
    OutboxIntent intent = intent(OutboxIntentType.CREATE_TICKET, OutboxStatus.IN_PROCESS);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    DispatchResult result = new DispatchResult(List.of(), Optional.empty());

    TransactionSynchronizationManager.initSynchronization();
    try {
      deliverer.commit(ROW_ID, CLAIM_TOKEN, result);
      // afterCommit callbacks are not fired automatically without a real transaction
      // verify the synchronization was registered and fire it manually
      TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(pendingTicketCreationCache).removeIfNoPendingCreateTicket(NEGOTIATION_ID);
  }

  @Test
  void commit_nonCreateTicket_doesNotTouchCache() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    DispatchResult result = new DispatchResult(List.of(), Optional.empty());

    TransactionSynchronizationManager.initSynchronization();
    deliverer.commit(ROW_ID, CLAIM_TOKEN, result);

    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    verify(pendingTicketCreationCache, never()).removeIfNoPendingCreateTicket(any());
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void commit_nullResult_doesNothing() {
    deliverer.commit(ROW_ID, CLAIM_TOKEN, null);

    verify(outboxIntentRepository, never()).findById(any());
    verify(ticketMappingRepository, never()).saveAll(any());
  }

  @Test
  void commit_persistenceThrows_doesNotMarkDelivered() {
    OutboxIntent intent = intent(OutboxIntentType.CREATE_TICKET, OutboxStatus.IN_PROCESS);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    DispatchResult result = new DispatchResult(List.of(ticketMapping()), Optional.empty());
    doThrow(new RuntimeException("db down")).when(ticketMappingRepository).saveAll(any());

    assertThrows(RuntimeException.class, () -> deliverer.commit(ROW_ID, CLAIM_TOKEN, result));

    assertEquals(OutboxStatus.IN_PROCESS, intent.getStatus());
    verify(outboxIntentRepository, never()).save(intent);
  }

  @Test
  void recordFailure_transientWithBudget_returnsToPendingWithBackoffAndClearsClaim() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    intent.setAttemptCount(0);
    intent.setClaimExpiresAt(Instant.now());
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    when(failureClassifier.classify(any())).thenReturn(FailureClass.TRANSIENT);

    deliverer.recordFailure(ROW_ID, CLAIM_TOKEN, new RuntimeException("5xx"));

    assertEquals(OutboxStatus.PENDING, intent.getStatus());
    assertEquals(1, intent.getAttemptCount());
    assertNull(intent.getClaimExpiresAt());
    assertNull(intent.getClaimToken());
    verify(outboxIntentRepository).save(intent);
  }

  @Test
  void recordFailure_tokenMismatch_doesNothing() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    intent.setClaimToken(UUID.randomUUID()); // different token — simulates reclaim by reaper
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));

    deliverer.recordFailure(ROW_ID, CLAIM_TOKEN, new RuntimeException("5xx"));

    verify(outboxIntentRepository, never()).save(any());
    assertEquals(OutboxStatus.IN_PROCESS, intent.getStatus());
  }

  @Test
  void recordFailure_transientExhausted_deadLetters() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    intent.setAttemptCount(MAX_ATTEMPTS - 1);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    when(failureClassifier.classify(any())).thenReturn(FailureClass.TRANSIENT);

    deliverer.recordFailure(ROW_ID, CLAIM_TOKEN, new RuntimeException("5xx"));

    assertEquals(OutboxStatus.DEAD_LETTER, intent.getStatus());
    assertNull(intent.getClaimExpiresAt());
    assertNull(intent.getClaimToken());
  }

  @Test
  void recordFailure_permanent_marksFailedPermanent() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    when(failureClassifier.classify(any())).thenReturn(FailureClass.PERMANENT);

    deliverer.recordFailure(ROW_ID, CLAIM_TOKEN, new RuntimeException("4xx"));

    assertEquals(OutboxStatus.FAILED_PERMANENT, intent.getStatus());
    assertNull(intent.getClaimExpiresAt());
    assertNull(intent.getClaimToken());
  }

  @Test
  void recordFailure_unexpected_deadLetters() {
    OutboxIntent intent = intent(OutboxIntentType.ADD_COMMENT, OutboxStatus.IN_PROCESS);
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(intent));
    when(failureClassifier.classify(any())).thenReturn(FailureClass.UNEXPECTED_ERROR);

    deliverer.recordFailure(ROW_ID, CLAIM_TOKEN, new RuntimeException("boom"));

    assertEquals(OutboxStatus.DEAD_LETTER, intent.getStatus());
    assertNull(intent.getClaimExpiresAt());
    assertNull(intent.getClaimToken());
  }

  private OutboxIntent intent(OutboxIntentType type, OutboxStatus status) {
    OutboxIntent intent =
        new OutboxIntent(
            1L, type, TargetKind.TICKET.name(), TICKET_ID, NEGOTIATION_ID, ORG_EXT_ID, "{}");
    intent.setRowId(ROW_ID);
    intent.setStatus(status);
    intent.setClaimToken(CLAIM_TOKEN);
    return intent;
  }

  private TicketMapping ticketMapping() {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "101"), "src-1", "42", ORG_EXT_ID);
  }
}
