package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Project;
import io.mutastic.api.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The project's resources, where its libraries find them: Maven's class directories hold both.
class ResourceCopyTest {
  @TempDir Path project;

  @Test
  void resourcesButNotClassesOrMetaInf() throws Exception {
    var classes = project.resolve("target/classes");
    var testClasses = project.resolve("target/test-classes");
    write(classes.resolve("com/example/Greeting.class"), "class");
    write(classes.resolve("com/example/Messages.properties"), "hello=Hello");
    write(classes.resolve("META-INF/services/com.example.Plugin"), "com.example.Impl");
    write(testClasses.resolve("com/example/Messages.properties"), "hello=Test");
    var copy = project.resolve("target/mutastic/resources-worker");
    write(copy.resolve("left/over.txt"), "a last run's");

    ResourceCopy.of(withDirectories(classes, testClasses), copy);

    assertThat(Files.readString(copy.resolve("com/example/Messages.properties")))
        .isEqualTo("hello=Test");
    assertThat(copy.resolve("com/example/Greeting.class")).doesNotExist();
    assertThat(copy.resolve("META-INF")).doesNotExist();
    assertThat(copy.resolve("left")).doesNotExist();
    assertThat(Files.readString(classes.resolve("com/example/Messages.properties")))
        .isEqualTo("hello=Hello");
  }

  private static void write(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static Project withDirectories(Path classes, Path testClasses) {
    return new Project(
        "app",
        Path.of("java"),
        List.of(),
        Path.of("."),
        List.of(classes),
        List.of(),
        List.of(testClasses),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        TestSelection.ALL,
        Map.of());
  }
}
