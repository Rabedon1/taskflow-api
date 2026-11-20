package io.github.rabedon1.taskflow.task.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Formato estable de paginación: no depende de la serialización interna de Spring Data. */
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  public PageResponse {
    content = List.copyOf(content);
  }

  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
