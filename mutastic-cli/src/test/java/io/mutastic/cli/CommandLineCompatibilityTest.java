package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/// The command line is a promise from 1.0.0: a command line that worked with a 1.0+ release works
/// with every later one of the same major version. So the last 1.0+ release's `commands.json`
/// (every command, option, variable and exit status it declared, as the docs' reference has it) is
/// kept in src/test/resources/released/<version>, and this build is held to it.
///
/// `lastStableRelease` (gradle.properties) names that release. Before 1.0.0 the command line may
/// change, so there's nothing to hold it to, and no earlier release's copy may stand in.
class CommandLineCompatibilityTest {
  private static final String LAST_STABLE = System.getProperty("mutastic.lastStableRelease", "");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /// What's kept is the last 1.0+ release's, and only once there is one.
  @Test
  void theKeptCopyIsTheLastStableReleases() throws IOException {
    List<String> versions;
    if (Files.isDirectory(released())) {
      try (Stream<Path> dirs = Files.list(released())) {
        versions = dirs.map(dir -> dir.getFileName().toString()).sorted().toList();
      }
    } else {
      versions = List.of();
    }

    assertThat(versions)
        .as("src/test/resources/released: the last 1.0+ release's copy (RELEASING.md)")
        .isEqualTo(LAST_STABLE.isEmpty() ? List.of() : List.of(LAST_STABLE));
  }

  @Test
  void theLastStableReleasesCommandLineStillWorks() throws IOException {
    if (LAST_STABLE.isEmpty()) {
      return;
    }
    var kept = JSON.readTree(released().resolve(LAST_STABLE).resolve("commands.json").toFile());

    assertThat(CommandLineCompatibility.breaks(kept, now()))
        .as("what %s's command line took, and this build doesn't", LAST_STABLE)
        .isEmpty();
  }

  /// The check itself, whatever is kept: this build keeps its own promises, and breaks those of a
  /// command line that took more.
  @Test
  void aCopyOfThisBuildHoldsAndOneThatTookMoreBreaks() {
    assertThat(CommandLineCompatibility.breaks(now(), now())).isEmpty();

    var more = (ObjectNode) now();
    ((ArrayNode) more.get("commands").get(0).get("options")).add("--gone");
    var threshold = option(more, "--threshold");
    threshold.put("kind", "flag");
    ((ObjectNode) threshold.get("gradle")).put("property", "-Pmutastic.gone");
    ((ArrayNode) option(more, "--experimental").get("choices")).add("gone");
    ((ArrayNode) more.get("environment")).addObject().put("name", "MUTASTIC_GONE");
    ((ArrayNode) more.get("exitCodes")).addObject().put("name", "9");

    assertThat(CommandLineCompatibility.breaks(more, now()))
        .containsExactly(
            "`run` no longer takes `--gone`",
            "`--threshold` was a `flag` option, and is a `value` one",
            "`--threshold` changed its gradle property from `-Pmutastic.gone` to"
                + " `-Pmutastic.threshold`",
            "`--experimental` no longer takes `gone`",
            "the variable `MUTASTIC_GONE` is gone",
            "the exit status `9` is gone");
  }

  /// This build's command line, as the docs' reference reads it.
  private static JsonNode now() {
    return JSON.readTree(DocsModel.json());
  }

  private static ObjectNode option(JsonNode model, String name) {
    return (ObjectNode)
        model.get("options").values().stream()
            .filter(option -> option.get("name").asString().equals(name))
            .findFirst()
            .orElseThrow();
  }

  /// The source copies.
  static Path released() {
    return Path.of(System.getProperty("mutastic.ids")).resolve("../resources/released").normalize();
  }
}
