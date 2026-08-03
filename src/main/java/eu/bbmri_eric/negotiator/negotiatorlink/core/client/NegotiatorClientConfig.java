package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
class NegotiatorClientConfig {

  @Bean
  OAuth2AuthorizedClientManager negotiatorAuthorizedClientManager(
      ClientRegistrationRepository registrations) {
    var clientService = new InMemoryOAuth2AuthorizedClientService(registrations);
    var manager =
        new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clientService);
    manager.setAuthorizedClientProvider(
        OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    return manager;
  }

  @Bean
  NegotiatorClient negotiatorClient(
      @Value("${negotiatorlink.negotiator.base-url}") String baseUrl,
      @Value("${negotiatorlink.negotiator.connect-timeout:PT5S}") Duration connectTimeout,
      @Value("${negotiatorlink.negotiator.read-timeout:PT30S}") Duration readTimeout,
      OAuth2AuthorizedClientManager authorizedClientManager) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(connectTimeout);
    requestFactory.setReadTimeout(readTimeout);

    RestClient restClient =
        RestClient.builder()
            .requestFactory(requestFactory)
            .requestInterceptor(
                (request, body, execution) -> {
                  var authorizeRequest =
                      OAuth2AuthorizeRequest.withClientRegistrationId("negotiator")
                          .principal("negotiator-link")
                          .build();
                  var authorizedClient = authorizedClientManager.authorize(authorizeRequest);
                  if (authorizedClient == null) {
                    log.error(
                        "Failed to obtain OAuth2 access token for client registration 'negotiator'");
                    throw new IllegalStateException(
                        "Could not obtain OAuth2 access token for Negotiator — "
                            + "check NEGOTIATOR_CLIENT_ID, NEGOTIATOR_CLIENT_SECRET and NEGOTIATOR_TOKEN_URI");
                  }
                  request
                      .getHeaders()
                      .setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
                  return execution.execute(request, body);
                })
            .build();
    return new NegotiatorClient(restClient, baseUrl);
  }
}
