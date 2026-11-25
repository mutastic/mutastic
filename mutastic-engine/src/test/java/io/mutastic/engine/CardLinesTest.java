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
///
/// One test for each group of operators, not one for all of them: mutation testing runs a test
/// again for every mutant it reaches, and a mutant in one operator's code reaches only the group
/// that runs it. Found a group at a time, the mutants are the ones found all at once, by id; only
/// which is a twin of another operator's differs, and no line here depends on that.
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

  private static final List<String> CONSTANTS =
      List.of(
          "mutastic/constant-value",
          "mutastic/string-constant",
          "mutastic/regex-constant",
          "mutastic/boolean-constant");
  private static final List<String> RETURNS =
      List.of("mutastic/return-value", "mutastic/replace-body");
  private static final List<String> ARITHMETIC =
      List.of(
          "mutastic/arithmetic-replacement",
          "mutastic/other-arithmetic",
          "mutastic/math",
          "mutastic/flip-increment",
          "mutastic/bitwise",
          "mutastic/remove-negation");
  private static final List<String> OPERANDS = List.of("mutastic/keep-operand");
  private static final List<String> COMPARISONS =
      List.of("mutastic/comparison", "mutastic/other-comparison", "mutastic/boundary");
  private static final List<String> CONDITIONS =
      List.of("mutastic/remove-conditional", "mutastic/logical-connector");
  private static final List<String> ARGUMENTS =
      List.of("mutastic/null-parameter", "mutastic/swap-arguments", "mutastic/drop-vararg");
  private static final List<String> CALLS =
      List.of("mutastic/omit-call", "mutastic/remove-discarded-call", "mutastic/remove-void-call");
  private static final List<String> STATEMENTS =
      List.of(
          "mutastic/remove-assignment",
          "mutastic/remove-field-write",
          "mutastic/remove-switch-arm",
          "mutastic/remove-throw",
          "mutastic/drop-catch-type");

  /// The rules on libraries' calls, by namespace, so a rule added to one is in its group.
  private static final List<String> LIBRARIES =
      List.of("library", "optional", "predicate", "stream");

  private static final List<List<String>> GROUPS =
      List.of(
          CONSTANTS,
          RETURNS,
          ARITHMETIC,
          OPERANDS,
          COMPARISONS,
          CONDITIONS,
          ARGUMENTS,
          CALLS,
          STATEMENTS,
          LIBRARIES);

  /// The mutants a group's test checks: ten groups of more than 50 each keep the whole's more than
  /// 500.
  private static final int CHECKED_IN_EACH = 50;

  @Test
  void everyOperatorIsInOneGroup() {
    var groups = new ArrayList<String>();
    for (var operator : OperatorSet.available(List.of())) {
      var in = GROUPS.stream().filter(group -> isIn(operator.id(), group)).toList();
      if (in.size() != 1) {
        groups.add("%s is in %d groups".formatted(operator.id(), in.size()));
      }
    }
    assertThat(groups).isEmpty();
  }

  @Test
  void constants() throws IOException {
    checked(CONSTANTS);
  }

  @Test
  void returns() throws IOException {
    checked(RETURNS);
  }

  @Test
  void arithmetic() throws IOException {
    assertThat(checked(ARITHMETIC).spread()).as("card's line after javac's").isPositive();
  }

  @Test
  void operands() throws IOException {
    checked(OPERANDS);
  }

  @Test
  void comparisons() throws IOException {
    checked(COMPARISONS);
  }

  @Test
  void conditions() throws IOException {
    assertThat(checked(CONDITIONS).spread()).as("card's line after javac's").isPositive();
  }

  @Test
  void arguments() throws IOException {
    checked(ARGUMENTS);
  }

  @Test
  void calls() throws IOException {
    checked(CALLS);
  }

  @Test
  void statements() throws IOException {
    checked(STATEMENTS);
  }

  @Test
  void libraries() throws IOException {
    checked(LIBRARIES);
  }

  /// How many mutants were checked, and how many of them have a card line after javac's.
  private record Checked(int mutants, int spread) {}

  /// Checks every mutant `operators` make of the fixtures, and that there are enough of them.
  private static Checked checked(List<String> operators) throws IOException {
    var sources = new SourceView(TRUTH, List.of(TRUTH));
    var listed = new MutantList(sources);
    var failures = new ArrayList<String>();
    int checked = 0;
    int spread = 0;
    for (var mutant : mutants(operators)) {
      if (sources.path(mutant).isEmpty()) {
        continue; // a stand-in for a library, which a run doesn't mutate
      }
      checked++;
      var span = sources.span(mutant);
      spread += span.last() > span.first() ? 1 : 0;
      failures.addAll(misplaced(mutant, span, sources, listed));
    }
    assertThat(failures).isEmpty();
    assertThat(checked).as("mutants of the truth fixtures").isGreaterThan(CHECKED_IN_EACH);
    return new Checked(checked, spread);
  }

  /// What's wrong with where `mutant`'s card puts it, if anything.
  private static List<String> misplaced(
      Mutant mutant, SourceView.Span span, SourceView sources, MutantList listed) {
    var failures = new ArrayList<String>();
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
    return failures;
  }

  /// Whether `id` is chosen by one of `words`, as `--operators` chooses: by its id or namespace.
  private static boolean isIn(String id, List<String> words) {
    return words.stream().anyMatch(word -> id.equals(word) || id.startsWith(word + "/"));
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

  /// Every mutant of every fixture, under `operators`.
  private static List<Mutant> mutants(List<String> operators) throws IOException {
    var policy = new Policy(List.of(), List.of(), List.of(), operators, List.of());
    var mutants = new ArrayList<Mutant>();
    for (var directory : fixtureDirectories()) {
      var models =
          compiled(directory).values().stream().map(bytes -> ClassFile.of().parse(bytes)).toList();
      var mutations = new Mutations(ClassFile.of(), policy);
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
