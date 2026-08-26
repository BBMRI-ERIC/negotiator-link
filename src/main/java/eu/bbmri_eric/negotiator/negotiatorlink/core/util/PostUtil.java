package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;

public final class PostUtil {

  private PostUtil() {}

  /**
   * Signs the given text with the sender's name and email if not already signed.
   *
   * @param text the text to be signed
   * @param sender the sender information
   * @return the signed text
   */
  public static String signPost(String text, Sender sender) {
    if (sender == null || isAlreadySigned(text)) {
      return text;
    }
    String name = sender.name();
    String email = sender.email();
    if (name == null && email == null) {
      return text;
    }
    String from = name != null ? name + (email != null ? " <" + email + ">" : "") : email;
    return "From: " + from + "\n\n" + text;
  }

  private static boolean isAlreadySigned(String text) {
    if (!text.startsWith("From: ")) {
      return false;
    }
    // Our signature is a single "From: ..." line immediately followed by a blank line.
    // A forwarded/quoted email starting with "From: " has further header lines (To:, Subject:)
    // before any blank line, so its first newline precedes the blank-line separator — which
    // distinguishes genuine content from a prefix we injected.
    int firstNewline = text.indexOf('\n');
    int blankLine = text.indexOf("\n\n");
    return blankLine > 0 && firstNewline == blankLine;
  }
}
