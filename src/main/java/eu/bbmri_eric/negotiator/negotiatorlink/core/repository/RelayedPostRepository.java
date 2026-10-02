package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.RelayedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.RelayedPostId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelayedPostRepository extends JpaRepository<RelayedPost, RelayedPostId> {}
