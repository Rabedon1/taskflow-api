package io.github.rabedon1.taskflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Contrato operativo que usa el pipeline: /actuator/health para saber si la app está viva y
 * /actuator/info para confirmar qué commit está desplegado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ActuatorIT {

  private static final ParameterizedTypeReference<Map<String, Object>> JSON =
      new ParameterizedTypeReference<>() {};

  @Value("${local.server.port}")
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    client =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (request, response) -> {})
            .build();
  }

  @Test
  void healthIsUp() {
    Map<String, Object> health = client.get().uri("/actuator/health").retrieve().body(JSON);

    assertThat(health).containsEntry("status", "UP");
  }

  @Test
  void readinessGroupExists() {
    assertThat(statusOf("/actuator/health/readiness")).isEqualTo(HttpStatus.OK);
    assertThat(statusOf("/actuator/health/liveness")).isEqualTo(HttpStatus.OK);
  }

  @Test
  @SuppressWarnings("unchecked")
  void infoExposesGitCommit() {
    Map<String, Object> info = client.get().uri("/actuator/info").retrieve().body(JSON);

    Map<String, Object> git = (Map<String, Object>) info.get("git");
    Map<String, Object> commit = (Map<String, Object>) git.get("commit");
    Map<String, Object> id = (Map<String, Object>) commit.get("id");
    // Contrato para scripts/verify-deploy.sh: .git.commit.id.full es el SHA completo (40 hex)
    assertThat(id.get("full")).asString().matches("[0-9a-f]{40}");
    assertThat(info).containsKey("build");
  }

  @Test
  void sensitiveEndpointsAreHidden() {
    assertThat(statusOf("/actuator/env")).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(statusOf("/actuator/beans")).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private HttpStatus statusOf(String path) {
    return HttpStatus.valueOf(
        client.get().uri(path).retrieve().toBodilessEntity().getStatusCode().value());
  }
}
