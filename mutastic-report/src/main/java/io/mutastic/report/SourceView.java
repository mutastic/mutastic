package io.mutastic.report;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// A mutant as the user wrote it: its source file and line, and the line as the mutant has it.
public final class SourceView {
  private final Path root;
  private final List<Path> sources;
  private final List<Path> testSources;
  private final Invocation invocation;

  /// `root` is the project directory, which locations are relative to.
  public SourceView(Path root, List<Path> sources) {
    this(root, sources, List.of());
  }

  /// With the test sources too, so suggested tests match the class they'd go into.
  public SourceView(Path root, List<Path> sources, List<Path> testSources) {
    this(root, sources, testSources, Invocation.HERE);
  }

  private SourceView(Path root, List<Path> sources, List<Path> testSources, Invocation invocation) {
    this.root = root;
    this.sources = List.copyOf(sources);
    this.testSources = List.copyOf(testSources);
    this.invocation = invocation;
  }

  /// How the person runs mutastic on this run's project, from where they ran it: what the commands
  /// the output offers need, to work as they're copied.
  public SourceView withInvocation(Invocation invocation) {
    return new SourceView(root, sources, testSources, invocation);
  }

  public Invocation invocation() {
    return invocation;
  }

  /// A test class's source file, relative to the project, if it's in the test sources.
  public Optional<String> testFile(String testClass) {
    var relative = testClass.replace('.', '/') + ".java";
    return testSources.stream()
        .map(source -> source.resolve(relative))
        .filter(Files::isRegularFile)
        .findFirst()
        .map(this::relative);
  }

  /// A test class's source, `com.example.PricingTest`, if it's in the test sources.
  public Optional<String> testSource(String testClass) {
    var relative = testClass.replace('.', '/') + ".java";
    return testSources.stream()
        .map(source -> source.resolve(relative))
        .filter(Files::isRegularFile)
        .findFirst()
        .map(
            file -> {
              try {
                return Files.readString(file);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  /// `src/main/java/com/example/Pricing.java`, relative to the project, or the class's own path if
  /// no source directory has it.
  public String file(Mutant mutant) {
    return sourceFile(mutant).map(this::relative).orElse(expectedPath(mutant));
  }

  /// The source file itself, for linking to, if it's there.
  public Optional<Path> path(Mutant mutant) {
    return sourceFile(mutant).map(SourceView::real);
  }

  /// The source file's text exactly as on disk, if it's there.
  public Optional<String> text(Mutant mutant) {
    return sourceFile(mutant)
        .map(
            file -> {
              try {
                return Files.readString(file);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  /// The source file's lines as written, if it's there.
  public Optional<List<String>> lines(Mutant mutant) {
    return sourceFile(mutant)
        .map(
            file -> {
              try {
                return Files.readAllLines(file);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  /// The changed line's text, stripped, if the source is there.
  public Optional<String> line(Mutant mutant) {
    var place = place(mutant);
    return lines(mutant)
        .filter(all -> place.line() >= 1 && place.line() <= all.size())
        .map(all -> all.get(place.line() - 1).strip());
  }

  /// Where a change is: its line, and 1-based columns on it with the end exclusive.
  public record Place(int line, int start, int end) {}

  /// The advice for a mutant, its jump read the way the source has it.
  public Advice advice(Mutant mutant) {
    return Advice.of(mutant, java.util.Map.of(), asWritten(mutant));
  }

  /// The advice with the inputs the covering tests passed, its jump read the way the source has it.
  public Advice advice(io.mutastic.api.MutantResult result) {
    return Advice.of(result.mutant(), result.inputs(), asWritten(result.mutant()));
  }

  /// Whether the mutant's jump tests the condition as written: the statement has the written form
  /// of the jump's condition and not its opposite. When it has both or neither, javac's usual
  /// inverted jump. A jump on a boolean goes by its opcode.
  boolean asWritten(Mutant mutant) {
    var operator = mutant.operator();
    if (operator != io.mutastic.api.BuiltIn.BOUNDARY
        && operator != io.mutastic.api.BuiltIn.NEGATE_CONDITIONAL
        && operator != io.mutastic.api.BuiltIn.REMOVE_CONDITIONAL) {
      return false;
    }
    var onBoolean = Advice.jumpsWhenTrue(mutant);
    if (onBoolean.isPresent()) {
      return onBoolean.get();
    }
    var inverted = Advice.jump(mutant.original(), mutant.operands(), false);
    var written = Advice.jump(mutant.original(), mutant.operands(), true);
    if (inverted.isEmpty() || written.isEmpty()) {
      return false;
    }
    var statement = String.join("\n", statement(mutant));
    return statement.contains(" " + written.get() + " ")
        && !statement.contains(" " + inverted.get() + " ");
  }

  /// The lines of the statement javac gives the mutant's line to: from that line to the one that
  /// ends it.
  private List<String> statement(Mutant mutant) {
    var all = lines(mutant).orElse(List.of());
    int first = mutant.line();
    if (first < 1 || first > all.size()) {
      return List.of();
    }
    return all.subList(first - 1, statementEnd(all, first));
  }

  /// The line that ends the statement starting on `first`, at most [#STATEMENT_LINES] on.
  private static int statementEnd(List<String> all, int first) {
    int last = first;
    while (last < all.size()
        && last - first < STATEMENT_LINES
        && !endsStatement(all.get(last - 1))) {
      last++;
    }
    return last;
  }

  /// The lines `first` to `last` of a source file, 1-based and inclusive.
  private record Statement(List<String> all, int first, int last) {}

  /// The longest statement searched for the changed operator.
  private static final int STATEMENT_LINES = 12;

  /// A removed condition's change, `` `balance > 0` is always true ``: the condition and its value.
  private static final java.util.regex.Pattern ALWAYS =
      java.util.regex.Pattern.compile("`(?<condition>[^`]+)` is always (?<value>true|false)");

  /// Where the mutant's change is in the source.
  ///
  /// javac gives a whole statement the line it starts on, so `return !over && location < 9` split
  /// over three lines is all "line 57", though the `<` is on line 59. So the operator is looked for
  /// on every line of the statement (up to the line that ends it): found exactly once, that's the
  /// place, to the column. Otherwise the place is the statement on javac's line, start to end. For
  /// a removed call it's the method's name; for a returned default, the `return` statement. Columns
  /// 1 to 2 of javac's line when the source isn't there.
  public Place place(Mutant mutant) {
    var all = lines(mutant).orElse(List.of());
    int first = mutant.line();
    if (first < 1 || first > all.size() || all.get(first - 1).isBlank()) {
      return new Place(Math.max(1, first), 1, 2);
    }
    var statement = new Statement(all, first, statementEnd(all, first));
    return token(mutant)
        .flatMap(token -> onlyPlaceOf(token, mutant.operator(), statement))
        .orElseGet(() -> wholeLine(all.get(first - 1), first));
  }

  /// Where the token is in the statement, if it's there exactly once.
  private static Optional<Place> onlyPlaceOf(
      String token, io.mutastic.api.Operator operator, Statement statement) {
    @Nullable Place found = null;
    int count = 0;
    for (int number = statement.first(); number <= statement.last(); number++) {
      var text = statement.all().get(number - 1);
      for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + 1)) {
        count++;
        found =
            operator.onCalls()
                ? new Place(number, at + 1, at + token.length())
                : operator == io.mutastic.api.BuiltIn.RETURN_DEFAULT
                    ? new Place(number, at + 1, text.stripTrailing().length() + 1)
                    : new Place(number, at + 2, at + token.length()); // inside its spaces
      }
    }
    return count == 1 && found != null ? Optional.of(found) : Optional.empty();
  }

  /// The whole line, from its first non-blank character to its end.
  private static Place wholeLine(String line, int number) {
    int indent = line.length() - line.stripLeading().length();
    return new Place(number, indent + 1, line.stripTrailing().length() + 1);
  }

  /// The source text the change is to: ` >= ` with its spaces, `record(` for a removed call,
  /// `return ` for a returned default.
  private Optional<String> token(Mutant mutant) {
    var advice = advice(mutant);
    return advice
        .before()
        .map(before -> " " + before + " ")
        .or(
            () ->
                mutant.operator().onCalls()
                    ? Optional.of(calledName(mutant.original()) + "(")
                    : mutant.operator() == io.mutastic.api.BuiltIn.RETURN_DEFAULT
                        ? Optional.of("return ")
                        : Optional.empty());
  }

  /// A line that ends a statement or opens or closes a block: the statement can't run past it.
  private static boolean endsStatement(String line) {
    var code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
    var end = code.stripTrailing();
    return end.endsWith(";") || end.endsWith("{") || end.endsWith("}");
  }

  /// `record` for `INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V`.
  static String calledName(String call) {
    var target = call.substring(call.indexOf(' ') + 1, call.indexOf('('));
    return target.substring(target.lastIndexOf('.') + 1);
  }

  /// The line with the change made, when it can be shown exactly: the changed operator appears on
  /// the line once, or the line is a single `return`.
  public Optional<String> mutatedLine(Mutant mutant, Advice advice) {
    return line(mutant)
        .flatMap(
            text -> {
              if (advice.before().isPresent() && advice.after().isPresent()) {
                var before = " " + advice.before().get() + " ";
                int at = text.indexOf(before);
                return at >= 0 && at == text.lastIndexOf(before)
                    ? Optional.of(text.replace(before, " " + advice.after().get() + " "))
                    : Optional.empty();
              }
              if (mutant.operator() == BuiltIn.REMOVE_CONDITIONAL) {
                return conditionReplaced(text, advice);
              }
              boolean singleReturn =
                  text.startsWith("return ")
                      && text.endsWith(";")
                      && text.indexOf("return ", 1) < 0;
              return advice.after().filter(after -> singleReturn && after.startsWith("return "));
            });
  }

  /// `return balance > 0;` → `return true;`: the condition as the source writes it, once on the
  /// line, replaced by the value it now always has. Nothing when the line doesn't write it so.
  private static Optional<String> conditionReplaced(String text, Advice advice) {
    var always = ALWAYS.matcher(advice.change());
    if (!always.matches()) {
      return Optional.empty();
    }
    var condition = always.group("condition");
    int at = text.indexOf(condition);
    return at >= 0 && at == text.lastIndexOf(condition)
        ? Optional.of(text.replace(condition, always.group("value")))
        : Optional.empty();
  }

  private Optional<Path> sourceFile(Mutant mutant) {
    return sources.stream()
        .map(source -> source.resolve(expectedPath(mutant)))
        .filter(Files::isRegularFile)
        .findFirst();
  }

  /// `com/example/Pricing.java`, for `com/example/Pricing$Tier` too.
  private static String expectedPath(Mutant mutant) {
    var name = mutant.className();
    int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
    return (dollar < 0 ? name : name.substring(0, dollar)) + ".java";
  }

  private String relative(Path file) {
    var absolute = real(file);
    var base = real(root);
    return absolute.startsWith(base) ? base.relativize(absolute).toString() : absolute.toString();
  }

  /// Links resolved: the build may name a directory through one (macOS's `/var` is `/private/var`)
  /// and the user through the other.
  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }
}
