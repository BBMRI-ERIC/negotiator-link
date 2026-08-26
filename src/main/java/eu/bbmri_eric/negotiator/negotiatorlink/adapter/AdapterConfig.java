package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdapterConfig {

  @Bean
  Map<HelpdeskType, HelpdeskCallbackAdapter> callbackAdapters(
      List<HelpdeskCallbackAdapter> callbackAdapters) {
    return callbackAdapters.stream()
        .collect(Collectors.toMap(HelpdeskCallbackAdapter::getType, adapter -> adapter));
  }

  @Bean
  Map<HelpdeskType, HelpdeskClient> helpDeskClients(List<HelpdeskClient> clients) {
    return clients.stream().collect(Collectors.toMap(HelpdeskClient::getType, client -> client));
  }
}
