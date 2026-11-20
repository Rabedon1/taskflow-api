package io.github.rabedon1.taskflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationIT {

  @Test
  void contextLoads() {}
}
