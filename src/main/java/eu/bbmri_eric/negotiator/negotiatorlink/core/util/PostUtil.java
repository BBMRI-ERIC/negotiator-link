package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

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
    String fromLine = buildFromLine(sender);
    if (fromLine == null) {
      return text;
    }
    return fromLine + "\n\n" + text;
  }

  /**
   * Decorates a post coming from Negotiator with a sender header for a help-desk ticket comment.
   *
   * @param text the text to be decorated
   * @param sender the sender information
   * @param roleNames the roles held by the sender
   * @return the signed text if sender is available, otherwise the original text
   */
  public static String signPostWithRoles(String text, Sender sender, Set<String> roleNames) {
    String fromLine = buildFromLine(sender);
    if (fromLine == null) {
      return text;
    }
    String roleLine = roleNames.isEmpty() ? null : "Role: " + String.join(", ", roleNames);
    String header = roleLine == null ? fromLine : fromLine + "\n" + roleLine;
    return header + "\n\n" + text;
  }

  /**
   * Inserts a {@code Role:} line under the header the integration itself produced when relaying a
   * help-desk comment into the Negotiator.
   *
   * @param text the already integration-signed text being relayed back
   * @param roleNames the roles to add, if any
   * @return the text with a {@code Role:} line inserted under the existing header, or the original
   *     text when there is no role to add, the header already carries one, or no header is present
   */
  public static String insertRoleUnderRelayedHeader(String text, Set<String> roleNames) {
    if (roleNames.isEmpty() || !startsWithSignerHeader(text) || hasRoleLine(text)) {
      return text;
    }
    int firstNewline = text.indexOf('\n');
    return text.substring(0, firstNewline)
        + "\nRole: "
        + String.join(", ", roleNames)
        + text.substring(firstNewline);
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
    if (name == null && email == null) {
      return null;
    }
    String from = name != null ? name + (email != null ? " <" + email + ">" : "") : email;
    return "From: " + from;
  }

  private static boolean startsWithSignerHeader(String text) {
    if (!text.startsWith("From: ")) {
      return false;
    }
    int blankLine = text.indexOf("\n\n");
    if (blankLine <= 0) {
      return false;
    }
    String[] headerLines = text.substring(0, blankLine).split("\n", -1);
    if (headerLines.length == 1) {
      return true;
    }
    return headerLines.length == 2 && headerLines[1].startsWith("Role: ");
  }

  /** Whether an already-signed header block carries a {@code Role: } line. */
  private static boolean hasRoleLine(String text) {
    int blankLine = text.indexOf("\n\n");
    int limit = blankLine < 0 ? text.length() : blankLine;
    for (String line : text.substring(0, limit).split("\n", -1)) {
      if (line.startsWith("Role: ")) {
        return true;
      }
    }
    return false;
  }

  public static String enumToText(String value) {
    return Arrays.stream(value.split("_"))
        .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
        .collect(Collectors.joining(" "));
  }
}
