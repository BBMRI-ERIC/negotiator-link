package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Resolves the {@code negotiationId} for a captured inbound event. */
@Slf4j
@Component
public class NegotiationIdResolver {

  private final ObjectMapper objectMapper;
  private final TicketMappingRepository ticketMappingRepository;

  public NegotiationIdResolver(
      ObjectMapper objectMapper, TicketMappingRepository ticketMappingRepository) {
    this.objectMapper = objectMapper;
    this.ticketMappingRepository = ticketMappingRepository;
  }

  /** Resolves the negotiation id for a Negotiator event from its raw body. */
  public Optional<String> resolveForNegotiatorEvent(byte[] rawBody) {
    try {
      JsonNode root = objectMapper.readTree(rawBody);
      JsonNode negotiationId = root.path("data").path("negotiationId");
      if (negotiationId.isMissingNode() || negotiationId.isNull()) {
        return Optional.empty();
      }
      return Optional.of(negotiationId.asText());
    } catch (Exception e) {
      log.warn("Could not resolve negotiationId from Negotiator event body", e);
      return Optional.empty();
    }
  }

  /** Resolves the negotiation id for a help desk callback via its ticket mapping. */
  public Optional<String> resolveForHelpdeskCallback(String ticketId, String organizationExtId) {
    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, organizationExtId);
    return mappings.stream().findFirst().map(m -> m.getId().getNegotiationId());
  }
}
