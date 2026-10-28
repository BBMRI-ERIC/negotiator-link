package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.AfterCommitMetrics;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.Link;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

  private static final long ROW_ID = 7L;
  private static final long INBOX_ROW_ID = 1L;
  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-7";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String RESOURCE_SOURCE_ID = "src-1";
  private static final String EVENT = "MARK_AS_CHECKING_AVAILABILITY";

  @Mock private OutboxIntentRepository outboxIntentRepository;
  @Mock private TicketMappingRepository ticketMappingRepository;
  @Captor private ArgumentCaptor<List<OutboxIntent>> savedCaptor;
  private OutboxService outboxService;

  @BeforeEach
  void setUp() {
    outboxService =
        new OutboxService(
            outboxIntentRepository,
            ticketMappingRepository,
            new AfterCommitMetrics(new SimpleMeterRegistry()));
  }

  @Test
  void enqueueRequiredFormComment_withMappingAndUiLink_savesSystemCommentWithHumanEventAndLink() {
    when(outboxIntentRepository.findById(ROW_ID))
        .thenReturn(Optional.of(sendResourceEventIntent()));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_SOURCE_ID))
        .thenReturn(Optional.of(ticketMapping()));

    outboxService.enqueueRequiredFormComment(ROW_ID, negotiationWithUiLink("http://nego/abc"));

    verify(outboxIntentRepository).saveAll(savedCaptor.capture());
    List<OutboxIntent> saved = savedCaptor.getValue();
    assertThat(saved).hasSize(1);
    OutboxIntent comment = saved.getFirst();
    assertThat(comment.getIntentType()).isEqualTo(OutboxIntentType.ADD_SYSTEM_COMMENT);
    assertThat(comment.getTargetKind()).isEqualTo(TargetKind.TICKET.name());
    assertThat(comment.getTargetId()).isEqualTo(TICKET_ID);
    assertThat(comment.getInboxRowId()).isEqualTo(INBOX_ROW_ID);

    String message =
        PayloadUtil.deserialize(comment.getPayload(), AddSystemCommentPayload.class).message();
    assertThat(message).contains("Mark As Checking Availability");
    assertThat(message).contains("Link: http://nego/abc");
  }

  @Test
  void enqueueRequiredFormComment_nullNegotiation_savesCommentWithEmptyLink() {
    when(outboxIntentRepository.findById(ROW_ID))
        .thenReturn(Optional.of(sendResourceEventIntent()));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_SOURCE_ID))
        .thenReturn(Optional.of(ticketMapping()));

    outboxService.enqueueRequiredFormComment(ROW_ID, null);

    verify(outboxIntentRepository).saveAll(savedCaptor.capture());
    String message =
        PayloadUtil.deserialize(
                savedCaptor.getValue().getFirst().getPayload(), AddSystemCommentPayload.class)
            .message();
    assertThat(message).endsWith("Link: ");
  }

  @Test
  void enqueueRequiredFormComment_noTicketMapping_savesNothing() {
    when(outboxIntentRepository.findById(ROW_ID))
        .thenReturn(Optional.of(sendResourceEventIntent()));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_SOURCE_ID))
        .thenReturn(Optional.empty());

    outboxService.enqueueRequiredFormComment(ROW_ID, negotiationWithUiLink("http://nego/abc"));

    verify(outboxIntentRepository, never()).saveAll(anyList());
  }

  @Test
  void enqueueRequiredFormComment_bulkIntentType_savesNothing() {
    OutboxIntent bulk =
        new OutboxIntent(
            INBOX_ROW_ID,
            OutboxIntentType.SEND_BULK_RESOURCE_EVENT,
            TargetKind.NEGOTIATION.name(),
            NEGOTIATION_ID,
            NEGOTIATION_ID,
            ORG_EXT_ID,
            "{}");
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(bulk));

    outboxService.enqueueRequiredFormComment(ROW_ID, negotiationWithUiLink("http://nego/abc"));

    verify(outboxIntentRepository, never()).saveAll(anyList());
  }

  @Test
  void enqueueRequiredFormComment_intentGone_savesNothing() {
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.empty());

    outboxService.enqueueRequiredFormComment(ROW_ID, negotiationWithUiLink("http://nego/abc"));

    verify(outboxIntentRepository, never()).saveAll(anyList());
  }

  private OutboxIntent sendResourceEventIntent() {
    OutboxIntent intent =
        new OutboxIntent(
            INBOX_ROW_ID,
            OutboxIntentType.SEND_RESOURCE_EVENT,
            TargetKind.NEGOTIATION.name(),
            NEGOTIATION_ID,
            NEGOTIATION_ID,
            ORG_EXT_ID,
            payloadJson());
    intent.setRowId(ROW_ID);
    return intent;
  }

  private String payloadJson() {
    try {
      return PayloadUtil.serialize(
          new SendResourceEventPayload(NEGOTIATION_ID, RESOURCE_SOURCE_ID, EVENT));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private TicketMapping ticketMapping() {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "101"),
        RESOURCE_SOURCE_ID,
        "42",
        ORG_EXT_ID);
  }

  private EntityModelNegotiationDTO negotiationWithUiLink(String href) {
    Link link = new Link();
    link.setHref(href);
    EntityModelNegotiationDTO negotiation = new EntityModelNegotiationDTO();
    negotiation.setLinks(Map.of("ui", link));
    return negotiation;
  }
}
