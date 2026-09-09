package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.signPost;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HelpdeskPostHandler implements HelpdeskCallbackHandler<PostMessageEvent> {

  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedPostRepository originatedPostRepository;
  private final NegotiatorClient negotiatorClient;

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.POST_MESSAGE;
  }

  @Override
  @Transactional
  public void handle(PostMessageEvent postMessageEvent, OrganizationConfig config) {
    String ticketId = postMessageEvent.ticketId();

    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, config.organizationExternalId());

    if (mappings.isEmpty()) {
      log.warn(
          "No ticket mappings found for ticket '{}' org '{}' — skipping post",
          ticketId,
          config.organizationExternalId());
      return;
    }

    TicketMapping anyMapping = mappings.getFirst();
    String negotiationId = anyMapping.getId().getNegotiationId();

    String signedText = signPost(postMessageEvent.postText(), postMessageEvent.sender());
    String textHash = sha256Hex(signedText);

    originatedPostRepository.save(
        new OriginatedPost(textHash, anyMapping.getOrganizationId(), ticketId));

    var post =
        new PostCreateDTO()
            .text(signedText)
            .organizationId(anyMapping.getOrganizationExtId())
            .type(PostCreateDTO.TypeEnum.PRIVATE);

    negotiatorClient.createPost(negotiationId, post, config);
    log.info(
        "Forwarded help desk post from ticket '{}' to negotiation '{}' as PRIVATE post",
        ticketId,
        negotiationId);
  }
}
