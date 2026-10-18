package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NegotiatorFacade {

  private final NegotiatorClient negotiatorClient;
  private final OrganizationConfigRegistry organizationConfigRegistry;
  private final OutboxIntentRepository outboxIntentRepository;

  public NegotiatorFacade(
      NegotiatorClient negotiatorClient,
      OrganizationConfigRegistry organizationConfigRegistry,
      OutboxIntentRepository outboxIntentRepository) {
    this.negotiatorClient = negotiatorClient;
    this.organizationConfigRegistry = organizationConfigRegistry;
    this.outboxIntentRepository = outboxIntentRepository;
  }

  /**
   * Used to fetch the negotiation behind a failed {@code SEND_RESOURCE_EVENT} outbox intent so a
   * user-facing "required form" comment can be enriched with the negotiation's UI link.
   */
  public Optional<EntityModelNegotiationDTO> findNegotiationForRequiredFormComment(Long rowId) {
    OutboxIntent intent = outboxIntentRepository.findById(rowId).orElse(null);
    if (intent == null || intent.getIntentType() != OutboxIntentType.SEND_RESOURCE_EVENT) {
      return Optional.empty();
    }
    try {
      SendResourceEventPayload payload =
          PayloadUtil.deserialize(intent.getPayload(), SendResourceEventPayload.class);
      return organizationConfigRegistry
          .findByExternalId(intent.getOrganizationExtId())
          .map(config -> negotiatorClient.getNegotiation(payload.negotiationId(), config));
    } catch (Exception e) {
      log.warn("Intent {} — could not fetch negotiation for required-form comment", rowId, e);
      return Optional.empty();
    }
  }
}
