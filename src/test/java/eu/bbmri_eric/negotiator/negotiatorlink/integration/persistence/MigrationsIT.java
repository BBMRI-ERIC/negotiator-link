package eu.bbmri_eric.negotiator.negotiatorlink.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.integration.IntegrationBase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationsIT extends IntegrationBase {

  @Autowired private DataSource dataSource;

  @Test
  void allMigrationsApply_andSchemaHistoryHasNoFailures() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
    Integer failed =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);

    assertThat(applied).isNotNull().isPositive();
    assertThat(failed).isZero();
  }

  @Test
  void checkTableExistence() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(tableExists(jdbc, "inbox_event")).isTrue();
    assertThat(tableExists(jdbc, "outbox_intent")).isTrue();
    assertThat(tableExists(jdbc, "ticket_mapping")).isTrue();
    assertThat(tableExists(jdbc, "originated_post")).isTrue();
    assertThat(tableExists(jdbc, "forwarded_negotiation_payload_attachment")).isTrue();

    assertThat(tableExists(jdbc, "originated_transition")).isFalse();
  }

  private static boolean tableExists(JdbcTemplate jdbc, String table) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?)",
            Boolean.class,
            table);
    return Boolean.TRUE.equals(exists);
  }
}
