package io.github.rabedon1.taskflow.task;

import io.github.rabedon1.taskflow.task.dto.PageResponse;
import io.github.rabedon1.taskflow.task.dto.TaskRequest;
import io.github.rabedon1.taskflow.task.dto.TaskResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TaskService {

  private final TaskRepository repository;

  public TaskService(TaskRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public TaskResponse create(TaskRequest request) {
    Task task = new Task();
    apply(request, task);
    return TaskResponse.from(repository.save(task));
  }

  public TaskResponse get(UUID id) {
    return TaskResponse.from(find(id));
  }

  public PageResponse<TaskResponse> list(TaskStatus status, Pageable pageable) {
    var page =
        status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
    return PageResponse.from(page.map(TaskResponse::from));
  }

  @Transactional
  public TaskResponse update(UUID id, TaskRequest request) {
    Task task = find(id);
    apply(request, task);
    return TaskResponse.from(task);
  }

  @Transactional
  public void delete(UUID id) {
    if (!repository.existsById(id)) {
      throw new TaskNotFoundException(id);
    }
    repository.deleteById(id);
  }

  private Task find(UUID id) {
    return repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
  }

  private static void apply(TaskRequest request, Task task) {
    task.setTitle(request.title().strip());
    task.setDescription(request.description());
    task.setStatus(request.status() == null ? TaskStatus.TODO : request.status());
    task.setDueDate(request.dueDate());
  }
}
