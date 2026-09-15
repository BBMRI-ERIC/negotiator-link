package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationEventHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventRouter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NegotiatorEventRouterTest {

  @Mock private NegotiationEventHandler handlerA;
  @Mock private NegotiationEventHandler handlerB;

  private NegotiatorEventRouter router;

  @BeforeEach
  void setUp() {
    when(handlerA.getSupportedEventType()).thenReturn("negotiation.state.updated");
    when(handlerB.getSupportedEventType()).thenReturn("negotiation.post.added");
    router = new NegotiatorEventRouter(new ObjectMapper(), List.of(handlerA, handlerB));
  }

  @Test
  void route_dispatchesToMatchingHandler() {
    byte[] body =
        "{\"type\":\"negotiation.state.updated\",\"data\":{\"negotiationId\":\"n1\"}}"
            .getBytes(StandardCharsets.UTF_8);

    router.route(body);

    verify(handlerA).handle(any());
    verify(handlerB, never()).handle(any());
  }

  @Test
  void route_unknownEventType_silentlyIgnored() {
    byte[] body = "{\"type\":\"negotiation.unknown\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);

    router.route(body);

    verify(handlerA, never()).handle(any());
    verify(handlerB, never()).handle(any());
  }

  @Test
  void route_missingTypeField_silentlyIgnored() {
    byte[] body = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);

    router.route(body);

    verify(handlerA, never()).handle(any());
    verify(handlerB, never()).handle(any());
  }

  @Test
  void route_missingDataField_silentlyIgnored() {
    byte[] body = "{\"type\":\"negotiation.state.updated\"}".getBytes(StandardCharsets.UTF_8);

    router.route(body);

    verify(handlerA, never()).handle(any());
  }

  @Test
  void route_invalidJson_silentlyIgnored() {
    byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);

    router.route(body);

    verify(handlerA, never()).handle(any());
    verify(handlerB, never()).handle(any());
  }
}
