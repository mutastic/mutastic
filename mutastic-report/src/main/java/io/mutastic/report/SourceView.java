package io.mutastic.report;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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
    return testPath(testClass).map(this::relative);
  }

  /// A test class's file, as Java or Kotlin. Java first, as it's what a test class's name has
  /// always found.
  private Optional<Path> testPath(String testClass) {
    var relative = testClass.replace('.', '/');
    return java.util.stream.Stream.of(".java", ".kt")
        .flatMap(
            extension -> testSources.stream().map(source -> source.resolve(relative + extension)))
        .filter(Files::isRegularFile)
        .findFirst();
  }

  /// Whether the mutant's source is Kotlin.
  boolean isKotlin(Mutant mutant) {
    return file(mutant).endsWith(".kt");
  }

  /// A test class's source, `com.example.PricingTest`, if it's in the test sources.
  public Optional<String> testSource(String testClass) {
    return testPath(testClass)
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
    return advice(mutant, java.util.Map.of());
  }

  /// The advice with the inputs the covering tests passed, its jump read the way the source has it.
  public Advice advice(io.mutastic.api.MutantResult result) {
    return advice(result.mutant(), result.inputs());
  }

  private Advice advice(Mutant mutant, java.util.Map<String, List<Long>> seen) {
    var advice = bytecodeAdvice(written(mutant), seen);
    return isKotlin(mutant) ? advice.reworded(asKotlinWrites(mutant)) : advice;
  }

  /// The mutant with its operands as its statement writes them: a string javac wrote in by the name
  /// the source gives it, `msg.equals(EXIT_CODE)` for `msg.equals("{}")`.
  Mutant written(Mutant mutant) {
    var operands = mutant.operands();
    if (operands.stream().noneMatch(operand -> operand.indexOf('"') >= 0)) {
      return mutant;
    }
    var written = WrittenOperands.of(operands, String.join("\n", statement(mutant)));
    return new Mutant(
        mutant.className(),
        mutant.methodName(),
        mutant.descriptor(),
        mutant.ordinal(),
        mutant.line(),
        mutant.operator(),
        mutant.original(),
        mutant.replacement(),
        mutant.skipped(),
        mutant.copies(),
        written,
        mutant.inlinedFrom());
  }

  /// `name.isEmpty()` is `length() == 0` to the bytecode, as kotlinc copies it in: the card says
  /// what the line does. And Kotlin's `length` has no brackets.
  private java.util.function.UnaryOperator<String> asKotlinWrites(Mutant mutant) {
    var text = rawLine(mutant);
    var empty = KOTLIN_EMPTY.matcher(text);
    if (empty.find()) {
      var written = empty.group();
      var compared = written.endsWith("isEmpty()") ? "length() == 0" : "length() > 0";
      return words -> words.replace("`" + compared + "`", "`" + written + "`");
    }
    return text.contains(".length") ? words -> words.replace("length()", "length") : words -> words;
  }

  private static final Pattern KOTLIN_EMPTY = Pattern.compile("[\\w.]*\\.is(Not)?Empty\\(\\)");

  private String rawLine(Mutant mutant) {
    var all = lines(mutant).orElse(List.of());
    return mutant.line() >= 1 && mutant.line() <= all.size() ? all.get(mutant.line() - 1) : "";
  }

  private Advice bytecodeAdvice(Mutant mutant, java.util.Map<String, List<Long>> seen) {
    return switch (mutant.operator()) {
      case BuiltIn.LOGICAL_CONNECTOR -> Advice.connector(mutant, seen, connective(mutant));
      case BuiltIn.CONSTANT_VALUE -> Advice.constant(mutant, seen, charactersOn(mutant));
      case BuiltIn.REMOVE_SWITCH_ARM -> Advice.switchArm(mutant, seen, charactersOn(mutant));
      default -> Advice.of(mutant, seen, asWritten(mutant));
    };
  }

  /// A number as the line has it: `'a'` for `97`, when the line has that letter in quotes and not
  /// the number. The mutant's own value goes the same way, if it's a character too.
  private java.util.function.UnaryOperator<String> charactersOn(Mutant mutant) {
    var all = lines(mutant).orElse(List.of());
    var text = mutant.line() >= 1 && mutant.line() <= all.size() ? all.get(mutant.line() - 1) : "";
    var today = mutant.replacement().split(" ", 4)[1];
    boolean quoted = isCharacter(today) && text.contains(quoted(today));
    return value -> quoted && isCharacter(value) ? quoted(value) : value;
  }

  /// Whether the number is a character that can be written in quotes as it is.
  private static boolean isCharacter(String value) {
    return value.matches("\\d{2,3}")
        && Integer.parseInt(value) >= ' '
        && Integer.parseInt(value) <= '~';
  }

  private static String quoted(String value) {
    return "'" + (char) Integer.parseInt(value) + "'";
  }

  /// The connector a connector mutant swaps, when its statement has just one `&&` or `||`; its two
  /// sides too, when the connector is on the mutant's line and they can be read off it.
  Optional<Advice.Connective> connective(Mutant mutant) {
    var statement = String.join("\n", statement(mutant));
    int ands = occurrences(statement, "&&");
    int ors = occurrences(statement, "||");
    if (ands + ors != 1) {
      return Optional.empty();
    }
    var connector = ands == 1 ? "&&" : "||";
    // javac's line, not `line(mutant)`, whose place is found from this advice.
    var all = lines(mutant).orElse(List.of());
    var text = mutant.line() >= 1 && mutant.line() <= all.size() ? all.get(mutant.line() - 1) : "";
    int at = text.indexOf(connector);
    if (at < 0) {
      return Optional.of(new Advice.Connective(connector, Optional.empty(), Optional.empty()));
    }
    return Optional.of(
        new Advice.Connective(
            connector, Sides.left(text, at), Sides.right(text, at + connector.length())));
  }

  private static int occurrences(String text, String token) {
    int count = 0;
    for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
      count++;
    }
    return count;
  }

  /// Whether the mutant's jump tests the condition as written: the statement has the written form
  /// of the jump's condition and not its opposite. When it has both or neither, javac's usual
  /// inverted jump. A jump on a boolean goes by its opcode.
  boolean asWritten(Mutant mutant) {
    var operator = mutant.operator();
    if (!Advice.onJump(operator)) {
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
                : operator == io.mutastic.api.BuiltIn.RETURN_VALUE
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
                    : mutant.operator() == io.mutastic.api.BuiltIn.RETURN_VALUE
                        ? Optional.of("return ")
                        : Optional.empty());
  }

  /// A line that ends a statement or opens or closes a block: the statement can't run past it.
  private static boolean endsStatement(String line) {
    var code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
    var end = code.stripTrailing();
    return end.endsWith(";") || end.endsWith("{") || end.endsWith("}");
  }

  /// `record` for `INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V`. kotlinc adds a
  /// hash to the name of a function taking an inline class (`Text--4IGK_g` for Compose's `Text`),
  /// after a `-` no Java name can have: the source calls it by what comes before.
  static String calledName(String call) {
    var target = call.substring(call.indexOf(' ') + 1, call.indexOf('('));
    var name = target.substring(target.lastIndexOf('.') + 1);
    int mangled = name.indexOf('-');
    return mangled > 0 ? name.substring(0, mangled) : name;
  }

  /// The line with the change made, when it can be shown exactly: the changed operator appears on
  /// the line once, or the line is a single `return`.
  public Optional<String> mutatedLine(Mutant mutant, Advice advice) {
    return line(mutant)
        .flatMap(
            text -> {
              if (mutant.operator() == BuiltIn.CONSTANT_VALUE) {
                return constantChanged(text, advice);
              }
              if (advice.before().isPresent() && advice.after().isPresent()) {
                return operatorChanged(written(mutant), advice, text);
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

  /// The line with the number changed, when it's on the line once and not in a string: one the
  /// source names, `LIMIT`, isn't there to change.
  private static Optional<String> constantChanged(String text, Advice advice) {
    var before = advice.before().orElseThrow();
    var after = advice.after().orElseThrow();
    var outsideStrings = text.replaceAll("\"[^\"]*\"", "\"\"");
    return replacedOnce(outsideStrings, before, after)
        .flatMap(_ -> replacedOnce(text, before, after));
  }

  /// The line with the comparison changed: `b > 1` found once on it, from the operands, so a
  /// condition split over lines changes where the mutant is, not whichever `>` javac's line has.
  /// Otherwise (the operands unknown, or a constant the source names, `-SMALL_OVERDRAFT` for
  /// `-1000`) only an operator the whole statement has once.
  private Optional<String> operatorChanged(Mutant mutant, Advice advice, String text) {
    var operands = mutant.operands();
    var before = advice.before().orElseThrow();
    var after = advice.after().orElseThrow();
    var exact =
        operands.size() == 2
            ? replacedOnce(
                text,
                "%s %s %s".formatted(operands.get(0), before, operands.get(1)),
                "%s %s %s".formatted(operands.get(0), after, operands.get(1)))
            : Optional.<String>empty();
    return exact.or(
        () ->
            replacedOnce(String.join("\n", statement(mutant)), " " + before + " ", "")
                .flatMap(_ -> replacedOnce(text, " " + before + " ", " " + after + " ")));
  }

  private static boolean startsAWord(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '.';
  }

  /// `text` with `found` replaced, when it's there exactly once as whole words: not run on into a
  /// name, a literal's suffix or a member (`1` in `1L`, `day` in `days`, `a` in `a.b`).
  private static Optional<String> replacedOnce(String text, String found, String replacement) {
    // A boundary only where `found` itself starts or ends with part of a word: ` > ` is its own.
    var whole =
        java.util.regex.Pattern.compile(
            (startsAWord(found.charAt(0)) ? "(?<![\\w.])" : "")
                + java.util.regex.Pattern.quote(found)
                + (startsAWord(found.charAt(found.length() - 1)) ? "(?![\\w.])" : ""));
    var at = whole.matcher(text);
    if (!at.find()) {
      return Optional.empty();
    }
    int start = at.start();
    return at.find()
        ? Optional.empty()
        : Optional.of(
            text.substring(0, start) + replacement + text.substring(start + found.length()));
  }

  /// `return balance > 0;` → `return true;`: the condition as the source writes it, once on the
  /// line, replaced by the value it now always has. Nothing when the line doesn't write it so.
  private static Optional<String> conditionReplaced(String text, Advice advice) {
    var always = ALWAYS.matcher(advice.change());
    if (!always.matches()) {
      return Optional.empty();
    }
    // Whole words: `wide < 1` is in `wide < 1L`, and replacing it there would leave `falseL`.
    return replacedOnce(text, always.group("condition"), always.group("value"));
  }

  /// Where the mutant's line is: for code copied in from another file, that file and no other, as
  /// the line is one of its.
  private Optional<Path> sourceFile(Mutant mutant) {
    var found =
        sources.stream()
            .map(source -> source.resolve(expectedPath(mutant)))
            .filter(Files::isRegularFile)
            .findFirst();
    return mutant.inlinedFrom().isPresent() ? found : found.or(() -> kotlinFile(mutant));
  }

  /// Where kotlinc's class came from: Kotlin doesn't name a file for a class. `ShelfKt`, a file's
  /// top-level functions, is `Shelf.kt`; `Shelf` is in `Shelf.kt` if there is one, or else in the
  /// first file of its package that mentions it.
  private Optional<Path> kotlinFile(Mutant mutant) {
    var java = expectedPath(mutant);
    int slash = java.lastIndexOf('/');
    var directory = slash < 0 ? "" : java.substring(0, slash);
    var name = java.substring(slash + 1, java.length() - ".java".length());
    var simple = name.endsWith("Kt") ? name.substring(0, name.length() - 2) : name;
    var mention = Pattern.compile("\\b" + Pattern.quote(simple) + "\\b");
    return sources.stream()
        .map(source -> source.resolve(directory))
        .filter(Files::isDirectory)
        .flatMap(inPackage -> kotlinFileIn(inPackage, simple, mention).stream())
        .findFirst();
  }

  private static Optional<Path> kotlinFileIn(Path directory, String simple, Pattern mention) {
    var named = directory.resolve(simple + ".kt");
    if (Files.isRegularFile(named)) {
      return Optional.of(named);
    }
    try (var files = Files.list(directory)) {
      return files
          .filter(file -> file.toString().endsWith(".kt"))
          .sorted()
          .filter(file -> mentions(file, mention))
          .findFirst();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean mentions(Path file, Pattern mention) {
    try {
      return mention.matcher(Files.readString(file)).find();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// `com/example/Pricing.java`, for `com/example/Pricing$Tier` too; or the file the mutant's code
  /// was copied in from.
  private static String expectedPath(Mutant mutant) {
    if (mutant.inlinedFrom().isPresent()) {
      return mutant.inlinedFrom().get();
    }
    var name = withoutComposableSingletons(mutant.className());
    int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
    return (dollar < 0 ? name : name.substring(0, dollar)) + ".java";
  }

  private static final String SINGLETONS = "ComposableSingletons$";

  /// `com/example/FocusAppKt` for `com/example/ComposableSingletons$FocusAppKt`: the Compose
  /// compiler keeps a file's composable lambdas in a class named for the file's.
  static String withoutComposableSingletons(String className) {
    int simple = className.lastIndexOf('/') + 1;
    return className.startsWith(SINGLETONS, simple)
        ? className.substring(0, simple) + className.substring(simple + SINGLETONS.length())
        : className;
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
