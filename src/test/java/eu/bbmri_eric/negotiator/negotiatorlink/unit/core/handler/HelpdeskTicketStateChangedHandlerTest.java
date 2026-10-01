package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskTicketStateChangedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketStateChangedEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.InvalidResource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.Rejection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HelpdeskTicketStateChangedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-42";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String RESOURCE_SOURCE_ID = "biobank:DE:MHH:collection1";
  private static final String MAPPED_STATE = "open";
  private static final String MAPPED_EVENT = "MARK_AS_CHECKING_AVAILABILITY";

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private ResourceTransitionService resourceTransitionService;

  private OrganizationConfig config;
  private HelpdeskTicketStateChangedHandler handler;

  @BeforeEach
  void setUp() {
    config =
        organizationConfig(
            ORG_EXT_ID,
            TicketStrategy.PER_RESOURCE,
            Map.of(),
            Map.of(),
            Map.of(MAPPED_STATE, MAPPED_EVENT));
    handler =
        new HelpdeskTicketStateChangedHandler(ticketMappingRepository, resourceTransitionService);
  }

  @Test
  void getSupportedType_isTicketStateChanged() {
    assertThat(handler.getSupportedType()).isEqualTo(HelpdeskCallbackType.TICKET_STATE_CHANGED);
  }

  @Test
  void handle_mappedStateWithMappings_appliesTransition() {
    List<TicketMapping> mappings = List.of(mapping(RESOURCE_SOURCE_ID));
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(mappings);
    when(resourceTransitionService.apply(mappings, MAPPED_EVENT, config))
        .thenReturn(Optional.empty());

    handler.handle(new TicketStateChangedEvent(TICKET_ID, MAPPED_STATE), config);

    verify(resourceTransitionService).apply(eq(mappings), eq(MAPPED_EVENT), eq(config));
  }

  @Test
  void handle_unmappedTicketState_doesNothing() {
    handler.handle(new TicketStateChangedEvent(TICKET_ID, "closed"), config);

    verifyNoInteractions(ticketMappingRepository, resourceTransitionService);
  }

  @Test
  void handle_noMappings_doesNotApplyTransition() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of());

    handler.handle(new TicketStateChangedEvent(TICKET_ID, MAPPED_STATE), config);

    verify(resourceTransitionService, never()).apply(any(), any(), any());
  }

  @Test
  void handle_rejectedTransition_dropsSilently() {
    List<TicketMapping> mappings = List.of(mapping(RESOURCE_SOURCE_ID));
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(mappings);
    when(resourceTransitionService.apply(mappings, MAPPED_EVENT, config))
        .thenReturn(
            Optional.of(
                new Rejection(
                    List.of(new InvalidResource(RESOURCE_SOURCE_ID, Set.of("STEP_AWAY"))), true)));

    handler.handle(new TicketStateChangedEvent(TICKET_ID, MAPPED_STATE), config);

    verify(resourceTransitionService).apply(eq(mappings), eq(MAPPED_EVENT), eq(config));
  }

  private TicketMapping mapping(String resourceSourceId) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "101"),
        resourceSourceId,
        "org-1",
        ORG_EXT_ID);
  }
}
