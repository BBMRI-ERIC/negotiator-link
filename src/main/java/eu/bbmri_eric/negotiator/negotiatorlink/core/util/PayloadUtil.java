package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PayloadUtil {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Serialize a payload to its JSON string for storage on the outbox.
   *
   * @param payload the payload record to serialize
   * @return the JSON representation
   * @throws IllegalArgumentException if payload is null
   */
  public static String serialize(Object payload) {
    if (payload == null) {
      throw new IllegalArgumentException("Payload must not be null");
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize payload", e);
    }
  }
}
