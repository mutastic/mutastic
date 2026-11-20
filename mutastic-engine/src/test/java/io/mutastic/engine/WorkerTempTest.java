package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/// What one run's tests add to a worker's temporary directory goes once they've answered; what was
/// there before they ran stays.
class WorkerTempTest {
  @Test
  void whatARunAddsGoesAndWhatWasThereStays() throws Exception {
    var temp = WorkerTemp.create();
    try {
      var held = Files.createDirectory(temp.directory().resolve("tomcat.8080"));
      Files.writeString(held.resolve("work.txt"), "kept");
      var before = temp.contents();

      var leftover = Files.writeString(temp.directory().resolve("shared.txt"), "hello");
      var nested = Files.createDirectories(temp.directory().resolve("junit-1/inner"));
      Files.writeString(nested.resolve("file.txt"), "x");
      temp.deleteAllBut(before);

      assertThat(leftover).doesNotExist();
      assertThat(temp.directory().resolve("junit-1")).doesNotExist();
      assertThat(held.resolve("work.txt")).hasContent("kept");
    } finally {
      temp.delete();
    }
    assertThat(temp.directory()).doesNotExist();
  }
}
