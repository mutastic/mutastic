package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.MutantList;
import io.mutastic.report.SourceView;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// `--at` runs a mutant from any line its card points at: javac's line, where the mutant's
/// statement starts, and the line its change is on, which a card, `mutants`, the events and the
/// reports all name. The two differ when a statement runs over several lines, so each is checked on
/// every mutant of every shape the truth fixtures compile, under every operator: the card's line
/// selects its mutant, javac's does, and so does each between them. And `--at` never selects by a
/// line the card doesn't show: the lines are within the statement the card shows, when it shows
/// one.
class CardLinesTest {
  private static final Path TRUTH = Path.of("src/test/resources/truth");

  /// The stand-ins for annotations some fixtures are said to be zero or above by.
  private static final List<String> ANNOTATIONS =
      List.of(
          "org.checkerframework.checker.index.qual.NonNegative",
          "androidx.annotation.IntRange",
          "jakarta.validation.constraints.PositiveOrZero");

  private static final Pattern PACKAGE = Pattern.compile("(?m)^package (?<name>[\\w.]+);");

  /// `src/…/Shapes.java:57:15`: the line `mutants` names a mutant's place by.
  private static final Pattern LISTED = Pattern.compile(" [^ ]+:(?<line>\\d+):\\d+ ");

  private static final Policy EVERY_OPERATOR =
      new Policy(List.of(), List.of(), List.of(), List.of("all"), List.of());

  @Test
  void aMutantIsOnEveryLineItsCardPointsAt() throws IOException {
    var sources = new SourceView(TRUTH, List.of(TRUTH));
    var listed = new MutantList(sources);
    var failures = new ArrayList<String>();
    int checked = 0;
    int spread = 0;
    for (var mutant : mutants()) {
      if (sources.path(mutant).isEmpty()) {
        continue; // a stand-in for a library, which a run doesn't mutate
      }
      checked++;
      var span = sources.span(mutant);
      spread += span.last() > span.first() ? 1 : 0;
      var named = LISTED.matcher(listed.line(mutant));
      var card = named.find() ? Integer.parseInt(named.group("line")) : -1;
      for (int line = mutant.line(); line <= card; line++) {
        if (!span.overlaps(line, line)) {
          failures.add("%s isn't on line %d, which its card shows".formatted(about(mutant), line));
        }
      }
      if (card < mutant.line() || !span.overlaps(card, card)) {
        failures.add(
            "%s: its card names line %d, javac %d".formatted(about(mutant), card, mutant.line()));
      }
      var diff = sources.diff(mutant, sources.advice(mutant));
      if (!diff.before().isEmpty() && (span.first() < diff.first() || span.last() > diff.last())) {
        failures.add(
            "%s is on lines %d to %d, outside the statement its card shows, %d to %d"
                .formatted(about(mutant), span.first(), span.last(), diff.first(), diff.last()));
      }
    }

    assertThat(failures).isEmpty();
    assertThat(checked).as("mutants of the truth fixtures").isGreaterThan(500);
    assertThat(spread).as("mutants whose card's line is after javac's").isPositive();
  }

  /// A mutant, as a failure names it: its id, class and method, operator and javac's line.
  private static String about(Mutant mutant) {
    return "%s (%s.%s, %s, line %d)"
        .formatted(
            mutant.id(),
            mutant.className(),
            mutant.methodName(),
            mutant.operator().id(),
            mutant.line());
  }

  /// Every mutant of every fixture, under every operator.
  private static List<Mutant> mutants() throws IOException {
    var mutants = new ArrayList<Mutant>();
    for (var directory : fixtureDirectories()) {
      var models =
          compiled(directory).values().stream().map(bytes -> ClassFile.of().parse(bytes)).toList();
      var mutations = new Mutations(ClassFile.of(), EVERY_OPERATOR);
      mutations.prepare(models);
      models.forEach(model -> mutants.addAll(mutations.find(model)));
    }
    return mutants;
  }

  /// The directories with a fixture of their own, a package named for the directory.
  private static List<Path> fixtureDirectories() throws IOException {
    try (Stream<Path> directories = Files.list(TRUTH)) {
      return directories.filter(Files::isDirectory).sorted().toList();
    }
  }

  /// A directory's fixtures, compiled with what's beside them: the stand-ins for the libraries they
  /// call, in the directory under the libraries' own packages, and the annotations.
  private static Map<String, byte[]> compiled(Path directory) throws IOException {
    var fixtures = new ArrayList<Javac.Source>();
    var alongside = new ArrayList<Javac.Source>();
    for (var file : javaFiles(directory)) {
      var source = source(file);
      (isFixture(directory, source) ? fixtures : alongside).add(source);
    }
    for (var annotation : ANNOTATIONS) {
      if (alongside.stream().noneMatch(source -> source.className().equals(annotation))
          && fixtures.stream().noneMatch(source -> source.className().equals(annotation))) {
        alongside.add(
            Javac.Source.read(TRUTH.resolve(annotation.replace('.', '/') + ".java"), annotation));
      }
    }
    return fixtures.isEmpty() ? Map.of() : Javac.compile(fixtures, alongside, "-g");
  }

  /// Whether `source` is one of the directory's own fixtures: in the package named for it.
  private static boolean isFixture(Path directory, Javac.Source source) {
    var name = source.className();
    var own = directory.getFileName() + ".";
    return name.startsWith(own) && !name.substring(own.length()).contains(".");
  }

  private static List<Path> javaFiles(Path directory) throws IOException {
    try (Stream<Path> files = Files.walk(directory)) {
      return files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
    }
  }

  /// A file as the class its package and name make it.
  private static Javac.Source source(Path file) throws IOException {
    var text = Files.readString(file);
    var found = PACKAGE.matcher(text);
    var simple = file.getFileName().toString().replace(".java", "");
    return new Javac.Source(found.find() ? found.group("name") + "." + simple : simple, text);
  }
}
