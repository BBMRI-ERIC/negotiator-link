package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.signPost;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreatePostPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HelpdeskPostHandler implements HelpdeskCallbackHandler<PostMessageEvent> {

  private static final String POST_REJECTED_MESSAGE =
      "This message could not be forwarded to the Negotiator at this time. Negotiation is in the state that does not allow posting messages.";

  private final TicketMappingRepository ticketMappingRepository;
  private final OriginatedPostRepository originatedPostRepository;
  private final NegotiatorClient negotiatorClient;

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.POST_MESSAGE;
  }

  @Override
  public List<PendingIntent> handle(PostMessageEvent postMessageEvent, OrganizationConfig config) {
    String ticketId = postMessageEvent.ticketId();

    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, config.organizationExternalId());
    TicketMapping anyMapping = mappings.getFirst();
    String negotiationId = anyMapping.getId().getNegotiationId();

    if (!arePrivatePostsEnabled(negotiationId, config)) {
      return List.of(rejectPostIntent(ticketId, negotiationId, config));
    }

    String signedText = signPost(postMessageEvent.postText(), postMessageEvent.sender());
    String textHash = sha256Hex(signedText);

    originatedPostRepository.save(
        new OriginatedPost(textHash, anyMapping.getOrganizationId(), ticketId));

    log.info(
        "Deciding help desk post from ticket '{}' as PRIVATE post to negotiation '{}'",
        ticketId,
        negotiationId);
    PendingIntent intent =
        new PendingIntent(
            OutboxIntentType.CREATE_POST,
            TargetKind.NEGOTIATION,
            negotiationId,
            negotiationId,
            config.organizationExternalId(),
            serialize(new CreatePostPayload(negotiationId, ticketId, signedText)));
    return List.of(intent);
  }

  private boolean arePrivatePostsEnabled(String negotiationId, OrganizationConfig config) {
    try {
      var negotiation = negotiatorClient.getNegotiation(negotiationId, config);
      return negotiation != null && negotiation.getPrivatePostsEnabled();
    } catch (Exception e) {
      log.error(
          "Could not determine whether negotiation '{}' accepts private posts — rejecting post: {}",
          negotiationId,
          e.getMessage());
      return false;
    }
  }

  private PendingIntent rejectPostIntent(
      String ticketId, String negotiationId, OrganizationConfig config) {
    log.info(
        "Negotiation '{}' for ticket '{}' does not accept private posts — rejecting post and "
            + "notifying ticket",
        negotiationId,
        ticketId);
    return new PendingIntent(
        OutboxIntentType.ADD_SYSTEM_COMMENT,
        TargetKind.TICKET,
        ticketId,
        negotiationId,
        config.organizationExternalId(),
        serialize(new AddSystemCommentPayload(POST_REJECTED_MESSAGE)));
  }
}
