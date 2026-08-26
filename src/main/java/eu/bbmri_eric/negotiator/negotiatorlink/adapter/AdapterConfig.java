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
  Map<HelpDeskType, HelpdeskCallbackAdapter> callbackAdapters(
      List<HelpdeskCallbackAdapter> callbackAdapters) {
    return callbackAdapters.stream()
        .collect(Collectors.toMap(HelpdeskCallbackAdapter::getType, adapter -> adapter));
  }

  @Bean
  Map<HelpDeskType, HelpdeskClient> helpDeskClients(List<HelpdeskClient> clients) {
    return clients.stream().collect(Collectors.toMap(HelpdeskClient::getType, client -> client));
  }
}
