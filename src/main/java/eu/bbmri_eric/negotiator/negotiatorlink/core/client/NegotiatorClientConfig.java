package eu.bbmri_eric.negotiator.negotiatorlink.core.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class NegotiatorClientConfig {

  @Bean
  NegotiatorClient negotiatorClient(
      @Value("${negotiatorlink.negotiator.base-url}") String baseUrl,
      @Value("${negotiatorlink.negotiator.issuer-uri}") String issuerUri,
      @Value("${negotiatorlink.negotiator.connect-timeout:PT5S}") Duration connectTimeout,
      @Value("${negotiatorlink.negotiator.read-timeout:PT30S}") Duration readTimeout) {
    var httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
    return new NegotiatorClient(restClientBuilder, baseUrl, issuerUri);
  }
}
