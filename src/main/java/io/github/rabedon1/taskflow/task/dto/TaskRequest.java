package io.github.rabedon1.taskflow.task.dto;

import io.github.rabedon1.taskflow.task.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Cuerpo de entrada para crear o reemplazar una tarea. Si {@code status} es null se usa TODO. */
public record TaskRequest(
    @NotBlank @Size(max = 120) String title,
    @Size(max = 1000) String description,
    TaskStatus status,
    LocalDate dueDate) {}
