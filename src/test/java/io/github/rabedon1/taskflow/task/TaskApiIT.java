package io.github.rabedon1.taskflow.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rabedon1.taskflow.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Prueba de punta a punta: HTTP real → controller → service → PostgreSQL real (Testcontainers). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TaskApiIT {

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
  void fullCrudLifecycle() {
    ResponseEntity<Map<String, Object>> created =
        client
            .post()
            .uri("/api/v1/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"title\":\"Preparar demo\",\"description\":\"Video del pipeline\"}")
            .retrieve()
            .toEntity(JSON);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) created.getBody().get("id");
    assertThat(created.getHeaders().getLocation())
        .hasToString("http://localhost:" + port + "/api/v1/tasks/" + id);

    ResponseEntity<Map<String, Object>> fetched =
        client.get().uri("/api/v1/tasks/{id}", id).retrieve().toEntity(JSON);
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody()).containsEntry("status", "TODO");

    ResponseEntity<Map<String, Object>> updated =
        client
            .put()
            .uri("/api/v1/tasks/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"title\":\"Preparar demo\",\"status\":\"DONE\"}")
            .retrieve()
            .toEntity(JSON);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody()).containsEntry("status", "DONE");

    Map<String, Object> done = client.get().uri("/api/v1/tasks?status=DONE").retrieve().body(JSON);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) done.get("content");
    assertThat(content).extracting(t -> t.get("id")).contains(id);

    assertThat(
            client
                .delete()
                .uri("/api/v1/tasks/{id}", id)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            client
                .get()
                .uri("/api/v1/tasks/{id}", id)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void openApiDocsAreServed() {
    ResponseEntity<Map<String, Object>> docs =
        client.get().uri("/v3/api-docs").retrieve().toEntity(JSON);

    assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(docs.getBody()).containsKey("openapi");
  }
}
