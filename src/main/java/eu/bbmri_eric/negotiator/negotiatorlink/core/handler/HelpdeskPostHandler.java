package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;
import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil.signPost;

import eu.bbmri_eric.negotiator.negotiatorlink.common.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.PostMessageEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.PostType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.CreatePostPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HelpdeskPostHandler implements HelpdeskEventHandler<PostMessageEvent> {

  private static final String POST_REJECTED_MESSAGE =
      "This message could not be forwarded to the Negotiator at this time. Negotiation is in the state that does not allow posting %s messages.";

  private final TicketMappingRepository ticketMappingRepository;
  private final NegotiatorClient negotiatorClient;

  @Override
  public HelpdeskCallbackType getSupportedType() {
    return HelpdeskCallbackType.POST_MESSAGE;
  }

  @Override
  public HandlerResult handle(PostMessageEvent postMessageEvent, OrganizationConfig config) {
    String ticketId = postMessageEvent.ticketId();
    PostType postType = postMessageEvent.postType();

    List<TicketMapping> mappings =
        ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            ticketId, config.organizationExternalId());
    TicketMapping anyMapping = mappings.getFirst();
    String negotiationId = anyMapping.getId().getNegotiationId();

    if (!arePostsEnabled(negotiationId, postType, config)) {
      return HandlerResult.of(List.of(rejectPostIntent(ticketId, negotiationId, postType, config)));
    }

    String signedText = signPost(postMessageEvent.postText(), postMessageEvent.sender());
    String textHash = sha256Hex(signedText);

    OriginatedPost originatedPost =
        new OriginatedPost(textHash, config.organizationExternalId(), ticketId);

    log.info(
        "Deciding help desk post from ticket '{}' as {} post to negotiation '{}'",
        ticketId,
        postType.name().toLowerCase(),
        negotiationId);
    PendingIntent intent =
        new PendingIntent(
            OutboxIntentType.CREATE_POST,
            TargetKind.NEGOTIATION,
            negotiationId,
            negotiationId,
            config.organizationExternalId(),
            serialize(
                new CreatePostPayload(
                    negotiationId,
                    ticketId,
                    signedText,
                    postMessageEvent.attachments(),
                    postType)));
    return HandlerResult.of(List.of(intent), originatedPost);
  }

  private boolean arePostsEnabled(
      String negotiationId, PostType postType, OrganizationConfig config) {
    var negotiation = negotiatorClient.getNegotiation(negotiationId, config);
    return switch (postType) {
      case PRIVATE -> negotiation.getPrivatePostsEnabled();
      case PUBLIC -> negotiation.getPublicPostsEnabled();
    };
  }

  private PendingIntent rejectPostIntent(
      String ticketId, String negotiationId, PostType postType, OrganizationConfig config) {
    log.info(
        "Negotiation '{}' for ticket '{}' does not accept {} posts — rejecting post and "
            + "notifying ticket",
        negotiationId,
        ticketId,
        postType.name().toLowerCase());
    return new PendingIntent(
        OutboxIntentType.ADD_SYSTEM_COMMENT,
        TargetKind.TICKET,
        ticketId,
        negotiationId,
        config.organizationExternalId(),
        serialize(
            new AddSystemCommentPayload(
                POST_REJECTED_MESSAGE.formatted(postType.name().toLowerCase()))));
  }
}
