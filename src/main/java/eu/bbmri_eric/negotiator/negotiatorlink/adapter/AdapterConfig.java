package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdapterConfig {

  @Bean
  Map<HelpDeskType, HelpDeskAdapter> helpDeskAdapters(List<HelpDeskAdapter> adapters) {
    return adapters.stream().collect(Collectors.toMap(HelpDeskAdapter::getType, a -> a));
  }
}
