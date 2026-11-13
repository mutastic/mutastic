package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.api.Project;
import io.mutastic.api.TestSelection;
import io.mutastic.engine.Engine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Mutant ids are a promise: an ignore, a baseline or a `verify` in a script names a mutant by its
/// id, so an id must name the same mutant in every later version while the method's code is
/// unchanged. This finds every mutant, under every operator mutastic has and those its own plugins
/// and rules files add, in a fixed set of class files (src/test/ids: each sample's main code and
/// the engine's fixtures, compiled once with JDK 17's javac and never again), and holds them to the
/// list kept in src/test/ids/ids.tsv. Only finding: no test runs.
///
/// An id that's gone, or that now names another mutant, fails it: that breaks what users have kept.
/// So does a new mutant of an operator already listed, until the list is written again, so the list
/// keeps holding every mutant. Only an operator that's new adds ids freely: write the list again
/// before a release, so the release's ids are held from then on.
///
/// To write it: `./gradlew :mutastic-cli:exportIds`, then read the diff.
class StableIdsTest {
  private static final Path IDS = Path.of(System.getProperty("mutastic.ids"));
  private static final Path PLUGINS_JAR = Path.of(System.getProperty("mutastic.pluginsJar"));
  private static final Path RULES =
      Path.of(System.getProperty("mutastic.docs")).resolve("../../data/plugins").normalize();
  private static final boolean WRITE = Boolean.getBoolean("mutastic.ids.write");
  private static final Path KEPT = IDS.resolve("ids.tsv");

  @TempDir Path work;

  @Test
  void everyKeptIdStillNamesItsMutant() throws IOException {
    var found = foundInEverySample();
    if (WRITE) {
      Files.writeString(KEPT, tsv(found), StandardCharsets.UTF_8);
    }
    var kept = read(KEPT);

    assertThat(moved(kept, found))
        .as("ids that are gone or name another mutant: users' ignores and baselines name these")
        .isEmpty();
    assertThat(added(kept, found))
        .as("new mutants of operators already kept: ./gradlew :mutastic-cli:exportIds")
        .isEmpty();
  }

  /// A kept id that's no longer found, or that names another mutant now.
  static List<String> moved(Map<String, String> kept, Map<String, String> found) {
    return kept.entrySet().stream()
        .filter(entry -> !entry.getValue().equals(found.get(entry.getKey())))
        .map(entry -> entry.getKey() + "\t" + entry.getValue() + " → " + found.get(entry.getKey()))
        .toList();
  }

  /// A found id that isn't kept, of an operator that is: an operator new since the list was written
  /// adds ids freely.
  static List<String> added(Map<String, String> kept, Map<String, String> found) {
    var operators =
        kept.values().stream().map(StableIdsTest::operatorOf).collect(Collectors.toSet());
    return found.entrySet().stream()
        .filter(entry -> !kept.containsKey(entry.getKey()))
        .filter(entry -> operators.contains(operatorOf(entry.getValue())))
        .map(entry -> entry.getKey() + "\t" + entry.getValue())
        .toList();
  }

  /// The operator, the third column of a mutant's description.
  private static String operatorOf(String description) {
    return description.split("\t", -1)[2];
  }

  /// Every sample's mutants, by id: sample, member, operator, line and change.
  private Map<String, String> foundInEverySample() throws IOException {
    var found = new TreeMap<String, String>();
    for (Path sample : samples()) {
      for (Mutant mutant : find(sample)) {
        found.put(mutant.id(), describe(sample.getFileName().toString(), mutant));
      }
    }
    return found;
  }

  private static List<Path> samples() throws IOException {
    try (Stream<Path> listed = Files.list(IDS)) {
      return listed.filter(Files::isDirectory).sorted().toList();
    }
  }

  /// The mutants in `classes`, under every operator, mutastic's plugins' and its rules files' too.
  private List<Mutant> find(Path classes) {
    var engine = new Engine(PLUGINS_JAR, work.resolve("worker.log"));
    return engine.find(project(classes), options()).all();
  }

  private Engine.Options options() {
    var plugins = new ArrayList<Path>(List.of(PLUGINS_JAR));
    Stream.of("bigdecimal.yaml", "reactive.yaml", "mutiny.yaml")
        .map(RULES::resolve)
        .forEach(plugins::add);
    Policy policy =
        new Configuration(List.of(), plugins, List.of(), List.of("all"), java.util.Map.of())
            .policy(work);
    return new Engine.Options(
        List.of(), false, 1, false, false, Set.of(), policy, Optional.empty());
  }

  private Project project(Path classes) {
    return new Project(
        classes.getFileName().toString(),
        Path.of("java"),
        List.of(),
        work,
        List.of(classes),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        TestSelection.ALL,
        Map.of());
  }

  /// `pricing com/example/pricing/Pricing#discount(I)I mutastic/boundary 17 IF_ICMPLT → IF_ICMPLE`
  private static String describe(String sample, Mutant mutant) {
    return String.join(
        "\t",
        sample,
        mutant.className() + "#" + mutant.methodName() + mutant.descriptor(),
        mutant.operator().id(),
        String.valueOf(mutant.line()),
        mutant.original() + " → " + mutant.replacement());
  }

  private static String tsv(Map<String, String> found) {
    return found.entrySet().stream()
        .sorted(Comparator.comparing((Map.Entry<String, String> entry) -> entry.getValue()))
        .map(entry -> entry.getKey() + "\t" + entry.getValue() + "\n")
        .collect(Collectors.joining());
  }

  private static Map<String, String> read(Path file) throws IOException {
    var kept = new TreeMap<String, String>();
    if (Files.exists(file)) {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        var tab = line.indexOf('\t');
        kept.put(line.substring(0, tab), line.substring(tab + 1));
      }
    }
    return kept;
  }
}
