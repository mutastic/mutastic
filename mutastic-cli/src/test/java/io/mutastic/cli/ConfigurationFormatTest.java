package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Rule;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/// `mutastic.yaml` is a promise from 1.0.0: a file that read in a 1.0+ release reads in every later
/// one, as the same rules, plugins, filters and operators. So the last 1.0+ release's files are
/// kept in src/test/resources/released/<version>, one to a directory (the configured sample's, and
/// the configuration guide's, which has every key), each beside how it read (`mutastic.yaml.read`),
/// and are read now and held to it. A key may be added; none may be taken away or read another way.
///
/// `lastStableRelease` (gradle.properties) names that release; [CommandLineCompatibilityTest]
/// checks it's the one kept. To write the `.read` files: `./gradlew :mutastic-cli:exportIds`, then
/// read the diff.
class ConfigurationFormatTest {
  private static final boolean WRITE = Boolean.getBoolean("mutastic.ids.write");
  private static final String LAST_STABLE = System.getProperty("mutastic.lastStableRelease", "");

  /// The directories of the kept files.
  static List<Path> released() throws IOException {
    var kept = CommandLineCompatibilityTest.released().resolve(LAST_STABLE);
    if (LAST_STABLE.isEmpty() || !Files.isDirectory(kept)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(kept)) {
      return files
          .filter(file -> file.getFileName().toString().equals(Configuration.FILE))
          .map(Path::getParent)
          .sorted()
          .toList();
    }
  }

  @ParameterizedTest(allowZeroInvocations = true)
  @MethodSource("released")
  void aReleasedConfigurationReadsAsItDid(Path directory) throws IOException {
    var read = describe(Configuration.read(directory));
    var kept = directory.resolve(Configuration.FILE + ".read");
    if (WRITE) {
      Files.writeString(kept, read, StandardCharsets.UTF_8);
    }

    assertThat(read)
        .as("%s reads differently: a mutastic.yaml that read before must read the same", directory)
        .isEqualTo(Files.exists(kept) ? Files.readString(kept, StandardCharsets.UTF_8) : "");
  }

  /// Every key is among the files kept, so taking one away fails one of them.
  @Test
  void theKeptFilesHaveEveryKey() throws IOException {
    var read = new StringBuilder();
    for (Path directory : released()) {
      read.append(describe(Configuration.read(directory)));
    }
    if (LAST_STABLE.isEmpty()) {
      return;
    }

    assertThat(read.toString())
        .contains("rule: skip ", "rule: mutate ", "plugin: ", "filter: ", "operator: ");
  }

  /// How a file reads, a line to each thing read from it.
  private static String describe(Configuration configuration) {
    var lines = new ArrayList<String>();
    for (Rule rule : configuration.rules()) {
      lines.add(
          "rule: %s %s, because %s"
              .formatted(rule.mutate() ? "mutate" : "skip", rule.target(), rule.reason()));
    }
    configuration
        .plugins()
        .forEach(
            plugin -> lines.add("plugin: " + plugin.toString().replace(File.separatorChar, '/')));
    configuration.filters().forEach(filter -> lines.add("filter: " + filter));
    configuration.operators().forEach(operator -> lines.add("operator: " + operator));
    return String.join("\n", lines) + "\n";
  }
}
