package io.github.rabedon1.taskflow.task;

import java.util.UUID;

public class TaskNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TaskNotFoundException(UUID id) {
    super("Task " + id + " not found");
  }
}
