package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdapterConfig {

  @Bean
  Map<HelpDeskType, HelpDeskAdapter> helpDeskAdapters(ZammadAdapter zammadAdapter) {
    return Map.of(HelpDeskType.ZAMMAD, zammadAdapter);
  }
}
