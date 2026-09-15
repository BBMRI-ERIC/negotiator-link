package eu.bbmri_eric.negotiator.negotiatorlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NegotiatorLinkApplication {

  public static void main(String[] args) {
    SpringApplication.run(NegotiatorLinkApplication.class, args);
  }
}
