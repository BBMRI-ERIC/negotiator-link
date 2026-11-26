package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PostUtil {

  /**
   * Decorates the given text with a sender header for a helpdesk comment relayed into the
   * Negotiator.
   *
   * @param text the text to be signed
   * @param sender the sender information
   * @return the signed text if sender is available, otherwise the original text
   */
  public static String signPost(String text, Sender sender) {
    return new PostBuilder(text).from(buildFromLine(sender)).build();
  }

  /**
   * Decorates a post coming from Negotiator with a sender header for a help-desk ticket comment.
   *
   * @param text the text to be decorated
   * @param sender the sender information
   * @param roleNames the roles held by the sender
   * @param isPublicPost whether the post is visible to all request participants
   * @return the decorated text, or the original text when there is no header line to add
   */
  public static String signPostWithRoles(
      String text, Sender sender, Set<String> roleNames, boolean isPublicPost) {
    return new PostBuilder(text)
        .from(buildFromLine(sender))
        .role(buildRoleLine(roleNames))
        .visibility(buildVisibilityLine(isPublicPost))
        .build();
  }

  /**
   * Inserts a {@code Role:} line and, for public posts, a {@code Visibility:} line under the header
   * the integration itself produced when relaying a help-desk comment into the Negotiator.
   *
   * @param text the already integration-signed text being relayed back
   * @param sender the sender information as a fallback
   * @param roleNames the roles to add, if any
   * @param isPublicPost whether the post is visible to all request participants
   * @return the text with the {@code Role:} and/or {@code Visibility:} lines inserted under the
   *     existing header, or the original text when there is nothing to add or no header is present
   */
  public static String signRelayedPost(
      String text, Sender sender, Set<String> roleNames, boolean isPublicPost) {
    boolean hasFromLine = isSingleFromHeader(text);
    int blankLine = text.indexOf("\n\n");
    String fromLine = hasFromLine ? text.substring(0, blankLine) : buildFromLine(sender);
    String body = hasFromLine ? text.substring(blankLine + 2) : text;
    return new PostBuilder(body)
        .from(fromLine)
        .role(buildRoleLine(roleNames))
        .visibility(buildVisibilityLine(isPublicPost))
        .build();
  }

  /**
   * Builds the {@code From: ...} line for the sender, or {@code null} if there is nothing to add.
   */
  private static String buildFromLine(Sender sender) {
    if (sender == null) {
      return null;
    }
    String name = sender.name();
    String email = sender.email();
    boolean hasName = StringUtils.isNotBlank(name);
    boolean hasEmail = StringUtils.isNotBlank(email);
    if (!hasName && !hasEmail) {
      return null;
    }
    String from = hasName ? name + (hasEmail ? " <" + email + ">" : "") : email;
    return "From: " + from;
  }

  /**
   * Whether the text is exactly a single {@code From: <sender>} header line, followed by a blank
   * line and the message body — the shape the integration itself produces when relaying a help-desk
   * comment into the Negotiator.
   */
  private static boolean isSingleFromHeader(String text) {
    if (!text.startsWith("From: ")) {
      return false;
    }
    int blankLine = text.indexOf("\n\n");
    if (blankLine <= 0) {
      return false;
    }
    return text.substring(0, blankLine).indexOf('\n') < 0;
  }

  public static String enumToText(String value) {
    return Arrays.stream(value.split("_"))
        .map(word -> StringUtils.capitalize(word.toLowerCase()))
        .collect(Collectors.joining(" "));
  }

  private static String buildRoleLine(Set<String> roleNames) {
    return roleNames.isEmpty() ? null : "Role: " + String.join(", ", roleNames);
  }

  private static String buildVisibilityLine(boolean isPublicPost) {
    return isPublicPost ? "Visibility: All request participants" : null;
  }

  private static final class PostBuilder {

    private final String text;
    private String fromLine;
    private String roleLine;
    private String visibilityLine;

    PostBuilder(String text) {
      this.text = text;
    }

    PostBuilder from(String fromLine) {
      this.fromLine = fromLine;
      return this;
    }

    PostBuilder role(String roleLine) {
      this.roleLine = roleLine;
      return this;
    }

    PostBuilder visibility(String visibilityLine) {
      this.visibilityLine = visibilityLine;
      return this;
    }

    String build() {
      StringBuilder header = new StringBuilder();
      appendLine(header, fromLine);
      if (StringUtils.isNotBlank(fromLine)) {
        appendLine(header, roleLine);
      }
      appendLine(header, visibilityLine);
      if (header.isEmpty()) {
        return text;
      }
      return header + "\n\n" + text;
    }

    private static void appendLine(StringBuilder header, String line) {
      if (StringUtils.isNotBlank(line)) {
        if (!header.isEmpty()) {
          header.append('\n');
        }
        header.append(line);
      }
    }
  }
}
