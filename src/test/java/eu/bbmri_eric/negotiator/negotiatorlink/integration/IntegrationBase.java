package eu.bbmri_eric.negotiator.negotiatorlink.integration;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationBase {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    try {
      POSTGRES.start();
    } catch (Throwable t) {
      log.error("Failure during static initialization", t);
      throw t;
    }
  }

  @Autowired private DataSource dataSource;

  @BeforeEach
  void truncateMutableTables() {
    new JdbcTemplate(dataSource)
        .execute(
            "TRUNCATE TABLE "
                + "outbox_intent, inbox_event, ticket_mapping, originated_post, "
                + "forwarded_negotiation_payload_attachment "
                + "RESTART IDENTITY CASCADE");
  }
}
