package io.github.rabedon1.taskflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.rabedon1.taskflow.task.dto.PageResponse;
import io.github.rabedon1.taskflow.task.dto.TaskRequest;
import io.github.rabedon1.taskflow.task.dto.TaskResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

  @Mock private TaskRepository repository;

  @InjectMocks private TaskService service;

  @Test
  void createDefaultsStatusToTodo() {
    when(repository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

    TaskResponse created = service.create(new TaskRequest("  Nueva tarea  ", null, null, null));

    ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(TaskStatus.TODO);
    assertThat(captor.getValue().getTitle()).isEqualTo("Nueva tarea");
    assertThat(created.status()).isEqualTo(TaskStatus.TODO);
  }

  @Test
  void getThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(id))
        .isInstanceOf(TaskNotFoundException.class)
        .hasMessageContaining(id.toString());
  }

  @Test
  void updateReplacesEditableFields() {
    UUID id = UUID.randomUUID();
    Task existing = new Task();
    existing.setTitle("Viejo");
    existing.setDescription("vieja");
    when(repository.findById(id)).thenReturn(Optional.of(existing));

    TaskResponse updated =
        service.update(
            id, new TaskRequest("Nuevo", "nueva", TaskStatus.DONE, LocalDate.of(2026, 12, 24)));

    assertThat(updated.title()).isEqualTo("Nuevo");
    assertThat(updated.description()).isEqualTo("nueva");
    assertThat(updated.status()).isEqualTo(TaskStatus.DONE);
    assertThat(updated.dueDate()).isEqualTo(LocalDate.of(2026, 12, 24));
  }

  @Test
  void deleteThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repository.existsById(id)).thenReturn(false);

    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(TaskNotFoundException.class);
    verify(repository, never()).deleteById(any());
  }

  @Test
  void deleteRemovesWhenPresent() {
    UUID id = UUID.randomUUID();
    when(repository.existsById(id)).thenReturn(true);

    service.delete(id);

    verify(repository).deleteById(id);
  }

  @Test
  void listFiltersByStatusWhenGiven() {
    Pageable pageable = PageRequest.of(0, 20);
    when(repository.findByStatus(TaskStatus.DONE, pageable))
        .thenReturn(new PageImpl<>(List.of(task("hecha")), pageable, 1));

    PageResponse<TaskResponse> page = service.list(TaskStatus.DONE, pageable);

    assertThat(page.content()).extracting(TaskResponse::title).containsExactly("hecha");
    assertThat(page.totalElements()).isEqualTo(1);
    verify(repository, never()).findAll(any(Pageable.class));
  }

  @Test
  void listAllWhenStatusNull() {
    Pageable pageable = PageRequest.of(1, 5);
    when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 7));

    PageResponse<TaskResponse> page = service.list(null, pageable);

    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(5);
    assertThat(page.totalElements()).isEqualTo(7);
    assertThat(page.totalPages()).isEqualTo(2);
    verify(repository, never()).findByStatus(any(), any());
  }

  private static Task task(String title) {
    Task task = new Task();
    task.setTitle(title);
    return task;
  }
}
