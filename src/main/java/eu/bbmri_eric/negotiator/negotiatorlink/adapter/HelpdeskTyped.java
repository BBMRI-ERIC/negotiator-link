package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;

/** Common identity for help desk adapters, used for auto-registration by {@link HelpDeskType}. */
public interface HelpdeskTyped {

  /** Returns the help desk type this adapter handles. */
  HelpDeskType getType();
}
