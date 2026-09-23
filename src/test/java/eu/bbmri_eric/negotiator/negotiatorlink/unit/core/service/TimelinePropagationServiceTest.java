package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermark;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.ForwardedTimelineWatermarkId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.ForwardedTimelineWatermarkRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.TimelinePropagationService;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.NegotiationTimelineEventDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.time.LocalDateTime;
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
class TimelinePropagationServiceTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-42";
  private static final LocalDateTime T8 = LocalDateTime.parse("2024-01-03T08:00:00");
  private static final LocalDateTime T11 = LocalDateTime.parse("2024-01-03T11:00:00");

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private ForwardedTimelineWatermarkRepository watermarkRepository;
  @Mock private HelpdeskClient helpdeskClient;
  @Captor private ArgumentCaptor<ForwardedTimelineWatermark> watermarkCaptor;

  private TimelinePropagationService service;
  private OrganizationConfig config;

  @BeforeEach
  void setUp() {
    service =
        new TimelinePropagationService(
            negotiatorClient, watermarkRepository, Map.of(HelpdeskType.ZAMMAD, helpdeskClient));
    config = TestUtil.organizationConfig("bbmri:DE:MHH", TicketStrategy.PER_RESOURCE);
  }

  @Test
  void propagate_whenNoWatermarkExists_forwardsAllEntriesAndSetsWatermark() {
    stubTimeline(entry("changed to Draft", T8), entry("changed to Under review", T11));
    stubNoWatermark();

    service.propagate(NEGOTIATION_ID, TICKET_ID, config);

    verify(helpdeskClient).addSystemComment(eq(TICKET_ID), eq("changed to Draft"), eq(config));
    verify(helpdeskClient)
        .addSystemComment(eq(TICKET_ID), eq("changed to Under review"), eq(config));
    verify(watermarkRepository).save(watermarkCaptor.capture());
    ForwardedTimelineWatermark saved = watermarkCaptor.getValue();
    assertThat(saved.getLastTimestamp()).isEqualTo(T11);
    assertThat(saved.getLastTimestampCount()).isEqualTo(1);
  }

  @Test
  void propagate_whenEntriesShareLastTimestamp_forwardsOnlyThoseBeyondCount() {
    stubTimeline(entry("first at 11", T11), entry("second at 11", T11));
    stubWatermark(T11, 1);

    service.propagate(NEGOTIATION_ID, TICKET_ID, config);

    verify(helpdeskClient).addSystemComment(eq(TICKET_ID), eq("second at 11"), eq(config));
    verify(helpdeskClient, never()).addSystemComment(eq(TICKET_ID), eq("first at 11"), eq(config));
    verify(watermarkRepository).save(watermarkCaptor.capture());
    assertThat(watermarkCaptor.getValue().getLastTimestamp()).isEqualTo(T11);
    assertThat(watermarkCaptor.getValue().getLastTimestampCount()).isEqualTo(2);
  }

  @Test
  void propagate_whenAllEntriesAlreadyForwarded_forwardsNothing() {
    stubTimeline(entry("changed to Draft", T8), entry("changed to Under review", T11));
    stubWatermark(T11, 1);

    service.propagate(NEGOTIATION_ID, TICKET_ID, config);

    verify(helpdeskClient, never()).addSystemComment(any(), any(), any());
    verify(watermarkRepository, never()).save(any());
  }

  @Test
  void propagate_whenNewerEntriesExist_forwardsOnlyNewerAndAdvancesTimestamp() {
    stubTimeline(entry("old at 8", T8), entry("new at 11", T11));
    stubWatermark(T8, 1);

    service.propagate(NEGOTIATION_ID, TICKET_ID, config);

    verify(helpdeskClient).addSystemComment(eq(TICKET_ID), eq("new at 11"), eq(config));
    verify(helpdeskClient, never()).addSystemComment(eq(TICKET_ID), eq("old at 8"), eq(config));
    verify(watermarkRepository).save(watermarkCaptor.capture());
    assertThat(watermarkCaptor.getValue().getLastTimestamp()).isEqualTo(T11);
    assertThat(watermarkCaptor.getValue().getLastTimestampCount()).isEqualTo(1);
  }

  @Test
  void propagate_whenAddSystemCommentThrows_advancesOnlyPastForwardedEntries() {
    stubTimeline(entry("first at 8", T8), entry("second at 11", T11));
    stubNoWatermark();
    org.mockito.Mockito.lenient()
        .doThrow(new RuntimeException("boom"))
        .when(helpdeskClient)
        .addSystemComment(eq(TICKET_ID), eq("second at 11"), eq(config));

    service.propagate(NEGOTIATION_ID, TICKET_ID, config);

    verify(helpdeskClient).addSystemComment(eq(TICKET_ID), eq("first at 8"), eq(config));
    verify(watermarkRepository).save(watermarkCaptor.capture());
    assertThat(watermarkCaptor.getValue().getLastTimestamp()).isEqualTo(T8);
    assertThat(watermarkCaptor.getValue().getLastTimestampCount()).isEqualTo(1);
  }

  @Test
  void propagate_whenTimelineEmpty_doesNothing() {
    when(negotiatorClient.getTimeline(NEGOTIATION_ID, config)).thenReturn(List.of());

    service.propagate(NEGOTIATION_ID, TICKET_ID, config);

    verify(helpdeskClient, never()).addSystemComment(any(), any(), any());
    verify(watermarkRepository, never()).save(any());
  }

  @Test
  void seed_whenCalled_writesTailWatermarkAndForwardsNothing() {
    stubTimeline(entry("changed to Draft", T8), entry("changed to Under review", T11));
    stubNoWatermark();

    service.seed(NEGOTIATION_ID, TICKET_ID, config);

    verify(helpdeskClient, never()).addSystemComment(any(), any(), any());
    verify(watermarkRepository).save(watermarkCaptor.capture());
    assertThat(watermarkCaptor.getValue().getLastTimestamp()).isEqualTo(T11);
    assertThat(watermarkCaptor.getValue().getLastTimestampCount()).isEqualTo(1);
  }

  @Test
  void seed_whenWatermarkAlreadyExists_isNoOp() {
    stubWatermark(T11, 1);

    service.seed(NEGOTIATION_ID, TICKET_ID, config);

    verify(negotiatorClient, never()).getTimeline(any(), any());
    verify(watermarkRepository, never()).save(any());
  }

  private void stubTimeline(NegotiationTimelineEventDTO... entries) {
    when(negotiatorClient.getTimeline(NEGOTIATION_ID, config)).thenReturn(List.of(entries));
  }

  private void stubNoWatermark() {
    when(watermarkRepository.findByIdForUpdate(
            new ForwardedTimelineWatermarkId(NEGOTIATION_ID, TICKET_ID)))
        .thenReturn(Optional.empty());
  }

  private void stubWatermark(LocalDateTime timestamp, int count) {
    ForwardedTimelineWatermark watermark =
        new ForwardedTimelineWatermark(
            new ForwardedTimelineWatermarkId(NEGOTIATION_ID, TICKET_ID), timestamp, count);
    when(watermarkRepository.findByIdForUpdate(
            new ForwardedTimelineWatermarkId(NEGOTIATION_ID, TICKET_ID)))
        .thenReturn(Optional.of(watermark));
  }

  private static NegotiationTimelineEventDTO entry(String text, LocalDateTime timestamp) {
    NegotiationTimelineEventDTO dto = new NegotiationTimelineEventDTO();
    dto.setTriggeredBy("actor");
    dto.setText(text);
    dto.setTimestamp(timestamp);
    return dto;
  }
}
