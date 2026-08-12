package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {

  private HashUtil() {}

  /**
   * Compute the SHA-256 hex digest of the given text (UTF-8 encoded)
   *
   * @param text to be hashed
   * @return the SHA-256 hex digest
   */
  public static String sha256Hex(String text) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
