package io.mutastic.cli;

import io.mutastic.cli.SourceTokens.Token;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// How many tests a test source declares, counted from its annotations, as JUnit and TestNG would
/// run them: a test is one, a `@RepeatedTest(3)` three, and a `@ParameterizedTest` one for each row
/// its static sources hold: each of a `@CsvSource`'s entries and its `textBlock`'s lines, each of a
/// `@ValueSource`'s values, each name an `@EnumSource` gives, each line of a `@CsvFileSource`'s
/// file, and one each for `@NullSource` and `@EmptySource`. Comments are left out: only the code
/// counts.
///
/// What only running says is counted as one, and marked as needing a run: a `@MethodSource`,
/// `@ArgumentsSource` or `@FieldSource` (code makes the rows), a `@TestFactory`, and an
/// `@EnumSource` with no names. A test a condition may turn off (`@DisabledIf…`, `@EnabledOnOs`, …)
/// keeps its rows, and is marked too: only a run says whether it's off. Rows under `@Disabled` or
/// JUnit 4's `@Ignore`, on the method or a class around it, or a TestNG test with `enabled =
/// false`, are disabled.
///
/// @param tests the rows, disabled ones too
/// @param disabled the rows turned off
/// @param dynamic the tests whose rows only a run counts, or only a run says are turned off
record TestRows(int tests, int disabled, int dynamic) {
  private static final TestRows NONE = new TestRows(0, 0, 0);

  private static final Set<String> TESTS =
      Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

  private static final Set<String> TYPES = Set.of("class", "interface", "object", "enum", "record");

  /// Sources whose rows code makes: a run counts them.
  private static final Set<String> DYNAMIC =
      Set.of(
          "MethodSource",
          "MethodSources",
          "ArgumentsSource",
          "ArgumentsSources",
          "FieldSource",
          "FieldSources",
          "TestFactory",
          "TestTemplate");

  /// JUnit 5's conditions, which only a run can decide: `@DisabledIf`, `@EnabledOnOs`,
  /// `@DisabledIfSystemProperty`, `@EnabledForJreRange`, `@DisabledInNativeImage`… An extension's
  /// own condition shows only in a run, which counts what it skips.
  private static final Pattern CONDITION =
      Pattern.compile("(?:Disabled|Enabled)(?:If|On|For|In)\\w*");

  private static final Pattern NUMBER = Pattern.compile("^\\s*(?:value\\s*=\\s*)?(?<count>\\d+)");

  static TestRows of(List<Path> testSources) {
    var total = NONE;
    for (var root : testSources) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (var files = Files.walk(root)) {
        for (var file : files.filter(TestRows::source).toList()) {
          total = total.plus(of(Files.readString(file, StandardCharsets.UTF_8), root));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return total;
  }

  private static boolean source(Path file) {
    var name = file.getFileName().toString();
    return name.endsWith(".java") || name.endsWith(".kt");
  }

  TestRows plus(TestRows other) {
    return new TestRows(tests + other.tests, disabled + other.disabled, dynamic + other.dynamic);
  }

  /// The rows one source declares; `root`, its source directory, finds a `@CsvFileSource`'s file.
  static TestRows of(String source, Path root) {
    var reader = new Reader(root);
    SourceTokens.of(source).forEach(reader::read);
    return reader.rows;
  }

  /// A class around a test: whether it, or one around it, turns its tests off, or may.
  ///
  /// @param depth the depth of its braces
  private record Around(int depth, boolean off, boolean conditional) {}

  /// The tokens in order: each test's annotations, in the classes around it.
  private static final class Reader {
    private final Path root;
    private final List<Token> annotations = new ArrayList<>();
    private final Deque<Around> classes = new ArrayDeque<>();
    private Optional<List<Token>> typeAnnotations = Optional.empty();
    private int depth;
    private TestRows rows = NONE;

    Reader(Path root) {
      this.root = root;
    }

    void read(Token token) {
      switch (token.kind()) {
        case ANNOTATION -> annotations.add(token);
        case WORD -> {
          if (TYPES.contains(token.text())) {
            typeAnnotations = Optional.of(List.copyOf(annotations));
            annotations.clear();
          }
        }
        case OPEN -> opened();
        case CLOSE -> closed();
        case END -> declared();
      }
    }

    private void opened() {
      depth++;
      typeAnnotations.ifPresent(
          type ->
              classes.push(
                  new Around(
                      depth, off(type) || offAround(), conditional(type) || conditionalAround())));
      typeAnnotations = Optional.empty();
      annotations.clear();
    }

    private void closed() {
      while (!classes.isEmpty() && classes.peek().depth() >= depth) {
        classes.pop();
      }
      depth--;
      annotations.clear();
    }

    /// A declaration's head ends: a method's, if the annotations say it's a test.
    private void declared() {
      if (annotations.stream().anyMatch(token -> TESTS.contains(token.text()))) {
        var test = new Test(root, conditionalAround());
        annotations.forEach(test::add);
        rows = rows.plus(test.rows(offAround() || off(annotations)));
      }
      annotations.clear();
    }

    private boolean offAround() {
      return classes.stream().anyMatch(Around::off);
    }

    private boolean conditionalAround() {
      return classes.stream().anyMatch(Around::conditional);
    }
  }

  /// `@Disabled`, `@Ignore`, or TestNG's `@Test(enabled = false)`.
  private static boolean off(List<Token> annotations) {
    return annotations.stream()
        .anyMatch(
            token ->
                token.text().equals("Disabled")
                    || token.text().equals("Ignore")
                    || (token.text().equals("Test")
                        && token.arguments().replace(" ", "").contains("enabled=false")));
  }

  private static boolean conditional(List<Token> annotations) {
    return annotations.stream().anyMatch(token -> CONDITION.matcher(token.text()).matches());
  }

  /// One test's rows, from its annotations one at a time.
  private static final class Test {
    private final Path root;
    private int count;
    private boolean dynamic; // rows only a run counts
    private boolean conditional; // rows only a run says are turned off
    private boolean parameterized;

    Test(Path root, boolean conditional) {
      this.root = root;
      this.conditional = conditional;
    }

    void add(Token annotation) {
      var arguments = annotation.arguments();
      switch (annotation.text()) {
        case "ParameterizedTest" -> parameterized = true;
        case "RepeatedTest" -> {
          var number = NUMBER.matcher(arguments);
          count += number.find() ? Integer.parseInt(number.group("count")) : 1;
        }
        case "Test", "NullSource", "EmptySource" -> count += 1;
        case "NullAndEmptySource" -> count += 2;
        case "CsvSource" -> count += csvRows(AnnotationArguments.of(arguments));
        case "ValueSource" -> count += AnnotationArguments.of(arguments).values();
        case "EnumSource" -> known(enumNames(AnnotationArguments.of(arguments)));
        case "CsvFileSource" -> known(CsvFiles.rows(AnnotationArguments.of(arguments), root));
        default -> {
          dynamic |= DYNAMIC.contains(annotation.text());
          conditional |= CONDITION.matcher(annotation.text()).matches();
        }
      }
    }

    /// Rows the annotations say, or a run counts when they don't.
    private void known(Optional<Integer> rows) {
      rows.ifPresentOrElse(found -> count += found, () -> dynamic = true);
    }

    TestRows rows(boolean off) {
      int rows = Math.max(1, count + (parameterized && dynamic ? 1 : 0));
      return new TestRows(rows, off ? rows : 0, dynamic || conditional ? 1 : 0);
    }
  }

  /// An `@EnumSource`'s names, when it gives them and runs those.
  private static Optional<Integer> enumNames(AnnotationArguments parsed) {
    var mode = parsed.named("mode").orElse("");
    return parsed.named("names").isPresent() && (mode.isEmpty() || mode.endsWith("INCLUDE"))
        ? Optional.of(parsed.elements("names"))
        : Optional.empty();
  }

  /// A `@CsvSource`'s rows: each entry of `value` (named or not) and each line of `textBlock` that
  /// isn't blank or a CSV comment.
  private static int csvRows(AnnotationArguments parsed) {
    int rows = parsed.values();
    var block = parsed.named("textBlock");
    if (block.isPresent()) {
      for (var literal : SourceTokens.strings(block.get())) {
        rows += (int) literal.lines().filter(CsvFiles::isRow).count();
      }
    }
    return rows;
  }

  /// The lines of a `@CsvFileSource`'s files.
  private static final class CsvFiles {
    private CsvFiles() {}

    static boolean isRow(String line) {
      var stripped = line.strip();
      return !stripped.isEmpty() && !stripped.startsWith("#");
    }

    /// Its files' rows, less those it skips; none when a file can't be read here.
    static Optional<Integer> rows(AnnotationArguments parsed, Path root) {
      var skip = parsed.named("numLinesToSkip").map(String::strip).orElse("0");
      var files = files(parsed, root);
      if (files.isEmpty() || !skip.matches("\\d+")) {
        return Optional.empty();
      }
      int rows = 0;
      for (var file : files) {
        try {
          var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
          rows +=
              Math.max(
                  0, (int) lines.stream().filter(CsvFiles::isRow).count() - Integer.parseInt(skip));
        } catch (IOException e) {
          return Optional.empty();
        }
      }
      return Optional.of(rows);
    }

    /// `resources`, from the test resources beside `root`, and `files`, from the project's
    /// directory, three above `root` (`src/test/java`).
    private static List<Path> files(AnnotationArguments parsed, Path root) {
      var files = new ArrayList<Path>();
      var resources = root.resolveSibling("resources");
      parsed
          .named("resources")
          .ifPresent(
              value ->
                  SourceTokens.strings(value)
                      .forEach(name -> files.add(resources.resolve(name.replaceFirst("^/", "")))));
      var project = root.toAbsolutePath().normalize();
      for (int up = 0; up < 3 && project.getParent() != null; up++) {
        project = project.getParent();
      }
      var base = project;
      parsed
          .named("files")
          .ifPresent(
              value -> SourceTokens.strings(value).forEach(name -> files.add(base.resolve(name))));
      return files;
    }
  }
}
