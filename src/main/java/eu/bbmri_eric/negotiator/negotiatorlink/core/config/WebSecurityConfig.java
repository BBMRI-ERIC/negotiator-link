package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

  @Bean
  RestClient.Builder restClientBuilder() {
    return RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
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
