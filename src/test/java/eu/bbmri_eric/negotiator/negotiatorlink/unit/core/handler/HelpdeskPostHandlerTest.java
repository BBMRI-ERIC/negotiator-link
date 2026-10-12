package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HandlerResult;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskPostHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreatePostPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HelpdeskPostHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-7";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String ORG_ID = "42";
  private static final String POST_TEXT = "Please grant access to our collection.";
  private static final Sender SENDER = new Sender("Jane Rep", "jane@mhh.de");
  private static final String SIGNED_TEXT = "From: Jane Rep <jane@mhh.de>\n\n" + POST_TEXT;

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private NegotiatorClient negotiatorClient;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private OrganizationConfig config;
  private HelpdeskPostHandler handler;

  @BeforeEach
  void setUp() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    handler = new HelpdeskPostHandler(ticketMappingRepository, negotiatorClient);
  }

  @Test
  void handle_happyPath_returnsOriginatedPostAndEmitsCreatePostIntent() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping()));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config))
        .thenReturn(negotiationWithPrivatePosts(true));

    HandlerResult result = handler.handle(callbackEvent(), config);

    assertThat(result.originatedPost()).isPresent();
    var post = result.originatedPost().get();
    assertThat(post.getOrganizationExtId()).isEqualTo(ORG_EXT_ID);
    assertThat(post.getTicketId()).isEqualTo(TICKET_ID);
    assertThat(post.getTextHash()).isEqualTo(HashUtil.sha256Hex(SIGNED_TEXT));

    List<PendingIntent> intents = result.intents();
    assertThat(intents).hasSize(1);
    PendingIntent intent = intents.getFirst();
    assertThat(intent.outboxIntentType()).isEqualTo(OutboxIntentType.CREATE_POST);
    assertThat(intent.targetKind()).isEqualTo(TargetKind.NEGOTIATION);
    assertThat(intent.targetId()).isEqualTo(NEGOTIATION_ID);
    assertThat(intent.organizationExtId()).isEqualTo(ORG_EXT_ID);
    CreatePostPayload payload = read(intent, CreatePostPayload.class);
    assertThat(payload.negotiationId()).isEqualTo(NEGOTIATION_ID);
    assertThat(payload.ticketId()).isEqualTo(TICKET_ID);
    assertThat(payload.signedText()).isEqualTo(SIGNED_TEXT);
    verify(negotiatorClient, never()).createPost(any(), any(), any());
  }

  @Test
  void handle_privatePostsDisabled_emitsSystemCommentIntentAndNoOriginatedPost() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping()));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config))
        .thenReturn(negotiationWithPrivatePosts(false));

    HandlerResult result = handler.handle(callbackEvent(), config);

    assertSystemCommentRejection(result.intents());
    assertThat(result.originatedPost()).isEmpty();
  }

  @Test
  void handle_privatePostsFlagNull_emitsSystemCommentIntentAndNoOriginatedPost() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping()));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config))
        .thenReturn(negotiationWithPrivatePosts(null));

    HandlerResult result = handler.handle(callbackEvent(), config);

    assertSystemCommentRejection(result.intents());
    assertThat(result.originatedPost()).isEmpty();
  }

  @Test
  void handle_getNegotiationFails_emitsSystemCommentIntentAndNoOriginatedPost() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping()));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config))
        .thenThrow(new RuntimeException("negotiator unavailable"));

    HandlerResult result = handler.handle(callbackEvent(), config);

    assertSystemCommentRejection(result.intents());
    assertThat(result.originatedPost()).isEmpty();
  }

  private void assertSystemCommentRejection(List<PendingIntent> intents) {
    assertThat(intents).hasSize(1);
    PendingIntent intent = intents.getFirst();
    assertThat(intent.outboxIntentType()).isEqualTo(OutboxIntentType.ADD_SYSTEM_COMMENT);
    assertThat(intent.targetKind()).isEqualTo(TargetKind.TICKET);
    assertThat(intent.targetId()).isEqualTo(TICKET_ID);
    AddSystemCommentPayload payload = read(intent, AddSystemCommentPayload.class);
    assertThat(payload.message()).isNotBlank();
  }

  private <T> T read(PendingIntent intent, Class<T> type) {
    try {
      return objectMapper.readValue(intent.payloadJson(), type);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private EntityModelNegotiationDTO negotiationWithPrivatePosts(Boolean enabled) {
    return new EntityModelNegotiationDTO().privatePostsEnabled(enabled);
  }

  private TicketMapping mapping() {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "resource-1"),
        "biobank:DE:MHH:col1",
        ORG_ID,
        ORG_EXT_ID);
  }

  private PostMessageEvent callbackEvent() {
    return new PostMessageEvent(TICKET_ID, POST_TEXT, SENDER);
  }
}
