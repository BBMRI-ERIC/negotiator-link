package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;

/** Common identity for help desk adapters, used for auto-registration by {@link HelpdeskType}. */
public interface HelpdeskTyped {

  /** Returns the help desk type this adapter handles. */
  HelpdeskType getType();
}
