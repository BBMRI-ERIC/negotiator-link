package eu.bbmri_eric.negotiator.negotiatorlink.integration.util;

import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public class DatabaseCleanupExtension implements BeforeEachCallback {

  private static final String TRUNCATE =
      "TRUNCATE TABLE "
          + "outbox_intent, inbox_event, ticket_mapping, originated_post, "
          + "forwarded_negotiation_payload_attachment "
          + "RESTART IDENTITY CASCADE";

  @Override
  public void beforeEach(@NonNull ExtensionContext context) {
    DataSource dataSource =
        SpringExtension.getApplicationContext(context).getBean(DataSource.class);
    new JdbcTemplate(dataSource).execute(TRUNCATE);
  }
}
