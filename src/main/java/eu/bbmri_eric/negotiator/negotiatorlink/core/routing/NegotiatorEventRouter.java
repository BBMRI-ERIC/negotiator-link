package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationEventHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NegotiatorEventRouter {

  private final ObjectMapper objectMapper;
  private final Map<String, NegotiationEventHandler> handlers;

  public NegotiatorEventRouter(ObjectMapper objectMapper, List<NegotiationEventHandler> handlers) {
    this.objectMapper = objectMapper;
    this.handlers =
        handlers.stream()
            .collect(
                Collectors.toMap(
                    NegotiationEventHandler::getSupportedEventType, Function.identity()));
  }

  public void route(byte[] body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (IOException e) {
      log.error("Failed to parse webhook body", e);
      return;
    }

    JsonNode typeNode = root.get("type");
    if (typeNode == null || typeNode.isNull()) {
      log.warn("Webhook body has no 'type' field — discarding");
      return;
    }

    String type = typeNode.asText();
    NegotiationEventHandler handler = handlers.get(type);
    if (handler == null) {
      log.debug("No handler registered for event type '{}' — ignoring", type);
      return;
    }

    JsonNode data = root.get("data");
    if (data == null || data.isNull()) {
      log.warn("Webhook of type '{}' has no 'data' field — discarding", type);
      return;
    }

    log.debug("Routing event type '{}' to {}", type, handler.getClass().getSimpleName());
    handler.handle(data);
  }
}
