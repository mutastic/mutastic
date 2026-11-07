package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.CallRule;
import io.mutastic.engine.CallRules;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/// The rules-file format is a promise: a rules file that read in a release reads in every later
/// one, as the same operators with the same changes and words. So each rules file a release shipped
/// (src/test/resources/rules/<version>: mutastic's own stream rules and the docs' examples, as they
/// were) is read now and held to how it read when kept (`<name>.read`, beside it). A key may be
/// added to the format; none may be taken away or read another way.
///
/// To write the `.read` files: `./gradlew :mutastic-cli:exportIds`, then read the diff.
class RulesFileFormatTest {
  private static final boolean WRITE = Boolean.getBoolean("mutastic.ids.write");

  static List<Path> released() throws IOException, URISyntaxException {
    var rules =
        Path.of(Objects.requireNonNull(RulesFileFormatTest.class.getResource("/rules")).toURI());
    try (Stream<Path> files = Files.walk(rules)) {
      return files.filter(file -> file.toString().endsWith(".yaml")).sorted().toList();
    }
  }

  @ParameterizedTest
  @MethodSource("released")
  void aReleasedRulesFileReadsAsItDid(Path file) throws IOException {
    var read = describe(CallRules.read(file));
    var name = file.getFileName().toString().replace(".yaml", ".read");
    var kept = keptBeside(file, name);
    if (WRITE) {
      Files.writeString(kept, read, StandardCharsets.UTF_8);
    }

    assertThat(read)
        .as("%s reads differently: a rules file that read before must read the same", file)
        .isEqualTo(Files.exists(kept) ? Files.readString(kept, StandardCharsets.UTF_8) : "");
  }

  /// The source copy beside the one on the test classpath, so writing it changes what's committed.
  private static Path keptBeside(Path file, String name) {
    var resources = Path.of(System.getProperty("mutastic.ids")).resolve("../resources").normalize();
    var version = file.getParent().getFileName().toString();
    return resources.resolve("rules").resolve(version).resolve(name);
  }

  /// Every rule as it was read, each field on a line of its own, sets in order.
  private static String describe(List<CallRule> rules) {
    return rules.stream().map(RulesFileFormatTest::describe).collect(Collectors.joining("\n"));
  }

  private static String describe(CallRule rule) {
    var fields = new TreeMap<String, Object>();
    fields.put("id", rule.id());
    fields.put("explanation", rule.explanation());
    fields.put("tier", rule.tier().word());
    fields.put("owners", rule.owners().stream().sorted().toList());
    fields.put("methods", rule.methods().stream().sorted().toList());
    fields.put("descriptors", rule.descriptors().stream().sorted().toList());
    fields.put("since", rule.since());
    fields.put("rewrite", rule.rewrite());
    fields.put("source", rule.source());
    fields.put("changed", rule.changed());
    fields.put("templates", new TreeMap<>(rule.templates()));
    return fields.entrySet().stream()
        .map(Map.Entry::toString)
        .collect(Collectors.joining("\n", "", "\n"));
  }
}
