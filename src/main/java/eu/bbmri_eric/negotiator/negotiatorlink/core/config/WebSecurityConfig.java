package eu.bbmri_eric.negotiator.negotiatorlink.core.config;

import static org.springframework.security.config.Customizer.withDefaults;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ManagementSecurityProperties.class)
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

  /**
   * Secures the Actuator endpoints. {@code /actuator/health} is public, every other endpoint
   * requires HTTP Basic authentication.
   */
  @Bean
  @Order(0)
  SecurityFilterChain managementFilterChain(HttpSecurity http) {
    http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  @Bean
  @Order(1)
  SecurityFilterChain filterChain(HttpSecurity http) {
    http.csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/negotiator", "/webhooks/helpdesk/**"))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, "/webhooks/negotiator")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/webhooks/helpdesk/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService managementUserDetailsService(
      ManagementSecurityProperties properties, PasswordEncoder passwordEncoder) {
    User.UserBuilder user =
        User.withUsername(properties.getUser())
            .password(passwordEncoder.encode(properties.getPassword()))
            .roles("ACTUATOR");
    return new InMemoryUserDetailsManager(user.build());
  }
}
