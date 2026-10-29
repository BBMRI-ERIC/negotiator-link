package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.bbmri_eric.negotiator.negotiatorlink.integration.util.IntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationBase
class ActuatorSecurityTest {

  private static final String USER = "test-actuator";
  private static final String PASSWORD = "test-actuator-password";

  @Autowired private MockMvc mockMvc;

  @Test
  void health_isPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void prometheus_withoutCredentials_isUnauthorized() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void prometheus_withBadCredentials_isUnauthorized() throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus").with(httpBasic(USER, "wrong")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void prometheus_withCredentials_exposesInstrumentedSeries() throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus").with(httpBasic(USER, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("outbox_queue_depth")))
        .andExpect(content().string(containsString("inbox_dead_letter_depth")))
        .andExpect(content().string(containsString("outbox-worker-0")));
  }
}
