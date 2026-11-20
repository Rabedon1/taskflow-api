package io.github.rabedon1.taskflow.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rabedon1.taskflow.TestcontainersConfiguration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TaskRepositoryIT {

  @Autowired private TaskRepository repository;

  @Autowired private TestEntityManager entityManager;

  @Test
  void savesAndGeneratesIdAndTimestamps() {
    Task task = new Task();
    task.setTitle("Configurar pipeline");

    Task saved = repository.saveAndFlush(task);

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
    assertThat(saved.getStatus()).isEqualTo(TaskStatus.TODO);
  }

  @Test
  void findByStatusReturnsOnlyMatching() {
    repository.saveAndFlush(newTask("a", TaskStatus.TODO));
    repository.saveAndFlush(newTask("b", TaskStatus.TODO));
    repository.saveAndFlush(newTask("c", TaskStatus.DONE));

    Page<Task> done = repository.findByStatus(TaskStatus.DONE, PageRequest.of(0, 10));

    assertThat(done.getContent()).extracting(Task::getTitle).containsExactly("c");
  }

  @Test
  void persistsAllEditableFields() {
    Task task = newTask("Escribir README", TaskStatus.IN_PROGRESS);
    task.setDescription("Incluir diagrama del pipeline");
    task.setDueDate(LocalDate.of(2026, 12, 1));
    Task saved = repository.saveAndFlush(task);
    entityManager.clear(); // fuerza a leer de PostgreSQL y no de la caché de JPA

    Task reloaded = repository.findById(saved.getId()).orElseThrow();

    assertThat(reloaded.getTitle()).isEqualTo("Escribir README");
    assertThat(reloaded.getDescription()).isEqualTo("Incluir diagrama del pipeline");
    assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
  }

  private static Task newTask(String title, TaskStatus status) {
    Task task = new Task();
    task.setTitle(title);
    task.setStatus(status);
    return task;
  }
}
