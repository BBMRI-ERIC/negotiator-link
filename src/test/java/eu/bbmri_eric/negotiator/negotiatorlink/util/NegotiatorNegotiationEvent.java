package eu.bbmri_eric.negotiator.negotiatorlink.util;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum NegotiatorNegotiationEvent {
  SUBMIT("SUBMITTED"),
  APPROVE("IN_PROGRESS"),
  DECLINE("DECLINED"),
  START("IN_PROGRESS"),
  PAUSE("PAUSED"),
  UNPAUSE("IN_PROGRESS"),
  ABANDON("ABANDONED"),
  CONCLUDE("CONCLUDED");

  private final String toState;

  public String toState() {
    return toState;
  }
}
