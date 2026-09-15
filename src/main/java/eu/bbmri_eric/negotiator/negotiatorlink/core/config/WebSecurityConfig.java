package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

  @Bean
  RestClient.Builder restClientBuilder(
      @Value("${negotiator-link.helpdesk.connect-timeout:PT5S}") Duration connectTimeout,
      @Value("${negotiator-link.helpdesk.read-timeout:PT30S}") Duration readTimeout) {
    var httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(requestFactory);
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) {
    http.csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/negotiator", "/webhooks/helpdesk/**"))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, "/webhooks/negotiator")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/webhooks/helpdesk/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
