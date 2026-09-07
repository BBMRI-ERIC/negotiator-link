package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

import java.util.Arrays;
import java.util.Optional;

public enum NegotiatorResourceEvent {
  MARK_AS_CHECKING_AVAILABILITY("CHECKING_AVAILABILITY"),
  STEP_AWAY("RESOURCE_UNAVAILABLE"),
  MARK_AS_UNAVAILABLE("RESOURCE_UNAVAILABLE"),
  MARK_AS_CURRENTLY_UNAVAILABLE_BUT_WILLING_TO_COLLECT("RESOURCE_UNAVAILABLE_WILLING_TO_COLLECT"),
  MARK_AS_AVAILABLE("RESOURCE_AVAILABLE"),
  INDICATE_ACCESS_CONDITIONS("ACCESS_CONDITIONS_INDICATED"),
  GRANT_ACCESS_TO_RESOURCE("RESOURCE_MADE_AVAILABLE");

  private final String targetState;

  NegotiatorResourceEvent(String targetState) {
    this.targetState = targetState;
  }

  public String targetState() {
    return targetState;
  }

  public static Optional<NegotiatorResourceEvent> fromEvent(String event) {
    if (event == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(e -> e.name().equals(event)).findFirst();
  }
}
