package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskPostHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedPostRepository originatedPostRepository;
  @Mock private NegotiatorClient negotiatorClient;

  private OrganizationConfig config;
  private HelpdeskPostHandler handler;

  @BeforeEach
  void setUp() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    handler =
        new HelpdeskPostHandler(
            ticketMappingRepository, originatedPostRepository, negotiatorClient);
  }

  @Test
  void handle_happyPath_savesOriginatedPostAndCreatesPrivatePost() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping()));

    handler.handle(callbackEvent(), config);

    var postCaptor = ArgumentCaptor.forClass(OriginatedPost.class);
    verify(originatedPostRepository).save(postCaptor.capture());
    var savedPost = postCaptor.getValue();
    assertThat(savedPost.getOrganizationId()).isEqualTo(ORG_ID);
    assertThat(savedPost.getTicketId()).isEqualTo(TICKET_ID);
    assertThat(savedPost.getTextHash()).isNotBlank();

    var dtoCaptor = ArgumentCaptor.forClass(PostCreateDTO.class);
    verify(negotiatorClient).createPost(eq(NEGOTIATION_ID), dtoCaptor.capture(), eq(config));
    var dto = dtoCaptor.getValue();
    assertThat(dto.getText()).isEqualTo("From: Jane Rep <jane@mhh.de>\n\n" + POST_TEXT);
    assertThat(dto.getType()).isEqualTo(PostCreateDTO.TypeEnum.PRIVATE);
  }

  @Test
  void handle_ticketNotFound_makesNoNegotiatorCalls() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of());

    handler.handle(callbackEvent(), config);

    verify(negotiatorClient, never()).createPost(any(), any(), any());
    verify(originatedPostRepository, never()).save(any());
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
