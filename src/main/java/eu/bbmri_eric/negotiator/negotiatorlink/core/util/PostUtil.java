package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;

public final class PostUtil {

  private PostUtil() {}

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
    return text.startsWith("From: ");
  }
}
