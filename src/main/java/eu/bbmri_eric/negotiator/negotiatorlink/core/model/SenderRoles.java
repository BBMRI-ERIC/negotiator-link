package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

/** The Negotiator roles held by the author of a post, used to decorate help-desk comments. */
public record SenderRoles(
    boolean author, boolean admin, boolean representativeOfAnyResource, boolean networkManager) {

  public static final SenderRoles NONE = new SenderRoles(false, false, false, false);

  /** Whether at least one role is held. */
  public boolean any() {
    return author || admin || representativeOfAnyResource || networkManager;
  }
}
