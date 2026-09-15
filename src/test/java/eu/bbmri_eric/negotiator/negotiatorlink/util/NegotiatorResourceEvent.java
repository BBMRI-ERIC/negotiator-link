package eu.bbmri_eric.negotiator.negotiatorlink.util;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum NegotiatorResourceEvent {
  MARK_AS_CHECKING_AVAILABILITY("CHECKING_AVAILABILITY"),
  STEP_AWAY("RESOURCE_UNAVAILABLE"),
  MARK_AS_UNAVAILABLE("RESOURCE_UNAVAILABLE"),
  MARK_AS_CURRENTLY_UNAVAILABLE_BUT_WILLING_TO_COLLECT("RESOURCE_UNAVAILABLE_WILLING_TO_COLLECT"),
  MARK_AS_AVAILABLE("RESOURCE_AVAILABLE"),
  INDICATE_ACCESS_CONDITIONS("ACCESS_CONDITIONS_INDICATED"),
  GRANT_ACCESS_TO_RESOURCE("RESOURCE_MADE_AVAILABLE");

  private final String targetState;

  public String targetState() {
    return targetState;
  }

  public static Map<String, String> asMap() {
    return Arrays.stream(values())
        .collect(Collectors.toMap(Enum::name, NegotiatorResourceEvent::targetState));
  }
}
