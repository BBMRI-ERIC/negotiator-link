package eu.bbmri_eric.negotiator.negotiatorlink.util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TestUtil {

  private TestUtil() {}

  public static String hmacSha1Hex(String secret, byte[] data)
      throws NoSuchAlgorithmException, InvalidKeyException {
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    return HexFormat.of().formatHex(mac.doFinal(data));
  }
}
