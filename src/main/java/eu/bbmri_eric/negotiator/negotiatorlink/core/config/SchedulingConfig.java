package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(
    prefix = "negotiator-link.scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {}
