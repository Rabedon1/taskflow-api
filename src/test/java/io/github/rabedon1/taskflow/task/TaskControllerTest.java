package io.github.rabedon1.taskflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.rabedon1.taskflow.task.dto.PageResponse;
import io.github.rabedon1.taskflow.task.dto.TaskRequest;
import io.github.rabedon1.taskflow.task.dto.TaskResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";

  @Autowired private MockMvc mvc;

  @MockitoBean private TaskService service;

  @Test
  void createReturns201WithLocation() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.create(any(TaskRequest.class))).thenReturn(response(id, "Tarea"));

    mvc.perform(json(post("/api/v1/tasks"), "{\"title\":\"Tarea\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", endsWith("/api/v1/tasks/" + id)))
        .andExpect(jsonPath("$.id").value(id.toString()));
  }

  @Test
  void createWithBlankTitleReturns400Problem() throws Exception {
    mvc.perform(json(post("/api/v1/tasks"), "{\"title\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("title"));
  }

  @Test
  void createWithTitleOver120Returns400() throws Exception {
    String title = "x".repeat(121);
    mvc.perform(json(post("/api/v1/tasks"), "{\"title\":\"" + title + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("title"));
  }

  @Test
  void malformedJsonReturns400Problem() throws Exception {
    mvc.perform(json(post("/api/v1/tasks"), "{\"title\":"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  void unknownStatusReturns400Problem() throws Exception {
    mvc.perform(json(post("/api/v1/tasks"), "{\"title\":\"t\",\"status\":\"FOO\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  void invalidUuidReturns400Problem() throws Exception {
    mvc.perform(get("/api/v1/tasks/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  void getMissingReturns404Problem() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.get(id)).thenThrow(new TaskNotFoundException(id));

    mvc.perform(get("/api/v1/tasks/" + id))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.detail", containsString(id.toString())));
  }

  @Test
  void deleteReturns204() throws Exception {
    UUID id = UUID.randomUUID();

    mvc.perform(delete("/api/v1/tasks/" + id)).andExpect(status().isNoContent());

    verify(service).delete(id);
  }

  @Test
  void deleteMissingReturns404() throws Exception {
    UUID id = UUID.randomUUID();
    doThrow(new TaskNotFoundException(id)).when(service).delete(id);

    mvc.perform(delete("/api/v1/tasks/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void listPassesStatusFilter() throws Exception {
    when(service.list(eq(TaskStatus.DONE), any(Pageable.class)))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

    mvc.perform(get("/api/v1/tasks").param("status", "DONE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void pageSizeIsCappedAt100() throws Exception {
    when(service.list(isNull(), any(Pageable.class)))
        .thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

    mvc.perform(get("/api/v1/tasks").param("size", "10000")).andExpect(status().isOk());

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(service).list(isNull(), captor.capture());
    assertThat(captor.getValue().getPageSize()).isEqualTo(100);
  }

  @Test
  void noStackTraceInErrors() throws Exception {
    when(service.get(any())).thenThrow(new IllegalStateException("db password=secret"));

    MvcResult result =
        mvc.perform(get("/api/v1/tasks/" + UUID.randomUUID()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("Unexpected error"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("trace", "exception", "secret");
  }

  private static TaskResponse response(UUID id, String title) {
    Instant now = Instant.now();
    return new TaskResponse(id, title, null, TaskStatus.TODO, null, now, now);
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
      String body) {
    return builder.contentType(MediaType.APPLICATION_JSON).content(body);
  }
}
