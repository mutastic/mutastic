package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.Advice;
import io.mutastic.report.SourceView;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Each card a condition's mutant gets says what the mutant does, checked against Java itself.
///
/// The fixture has a method for each way javac compiles a condition. For every mutant of one, the
/// card's claim is written into the source: "`x > 0` is always true" puts `Boolean.TRUE` where `x >
/// 0` was, "`>=` becomes `>`" changes the operator, "reversed" negates the condition. That source,
/// compiled, must behave as the mutant does on every input. The check doesn't share the report's
/// reading of the bytecode: a card that has a jump the wrong way round fails here, as the first
/// test of an `||` once did.
class CardTruthTest {
  private static final Path FIXTURE = Path.of("src/test/resources/truth");
  private static final String CLASS = "shapes.Shapes";

  /// Another class, whose static a condition reads.
  private static final Path OTHER = FIXTURE.resolve("shapes/Other.java");

  /// The predicates the conditions call: not conditions of their own.
  private static final Set<String> HELPERS = Set.of("even", "small", "atLeastOne", "isAbove");

  private static final Set<BuiltIn> CONDITIONS =
      Set.of(
          BuiltIn.BOUNDARY,
          BuiltIn.COMPARISON,
          BuiltIn.REMOVE_CONDITIONAL,
          BuiltIn.OTHER_COMPARISON);

  private static final Pattern ALWAYS =
      Pattern.compile("`(?<condition>[^`]+)` is always (?<value>true|false)");
  private static final Pattern CONSTANT =
      Pattern.compile("static final int (?<name>[A-Z_]+) = (?<value>-?\\d+);");

  private static final Pattern LONG_LITERAL = Pattern.compile("\\b(\\d+)[lL]\\b");

  private static final Pattern LITERAL = Pattern.compile("\\b(?:true|false)\\b");
  private static final Pattern REVERSED = Pattern.compile("with `(?<condition>[^`]+)` reversed");

  /// A comparison's headline: the inputs where its mutant differs, `with `a > b`` or `at `a == b``.
  private static final Pattern WHERE = Pattern.compile("(?:with|at) `(?<where>[^`]+)`$");

  private static final int LOW = -4;
  private static final int HIGH = 5;

  @TempDir Path temp;

  /// One mutant's card, and the source that says what one of its claims says it does: what happens
  /// to the condition, or the changed line the card shows.
  private record Claim(Mutant mutant, Advice advice, String variant, String source, String what) {}

  @Test
  void everyConditionsCardSaysWhatItsMutantDoes() throws Exception {
    var original = compile(List.of(FIXTURE.resolve("shapes/Shapes.java"), OTHER), "original");
    var bytes = Files.readAllBytes(original.resolve("shapes/Shapes.class"));
    var model = ClassFile.of().parse(bytes);
    var mutations =
        new Mutations(
            ClassFile.of(),
            new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));
    var sources = new SourceView(FIXTURE, List.of(FIXTURE));
    var text = Files.readString(FIXTURE.resolve("shapes/Shapes.java"));

    var failures = new ArrayList<String>();
    var claims = new ArrayList<Claim>();
    var mutants =
        mutations.find(model).stream()
            .filter(mutant -> mutant.skipped().isEmpty())
            .filter(mutant -> CONDITIONS.contains(mutant.operator()))
            .filter(mutant -> !HELPERS.contains(mutant.methodName()))
            .toList();
    for (var mutant : mutants) {
      var advice = sources.advice(mutant);
      var variant = "Shapes" + claims.size();
      claim(text, mutant, advice, variant)
          .ifPresentOrElse(
              source -> claims.add(new Claim(mutant, advice, variant, source, "its words")),
              () ->
                  failures.add(describe(mutant, advice) + "\n  its condition isn't in the source"));
      headline(text, mutant, advice, "Shapes" + claims.size())
          .ifPresent(
              source ->
                  claims.add(
                      new Claim(mutant, advice, "Shapes" + claims.size(), source, "its headline")));
      var shown = sources.mutatedLine(mutant, advice);
      if (shown.isPresent()) {
        var lineVariant = "Shapes" + claims.size();
        claims.add(
            new Claim(
                mutant,
                advice,
                lineVariant,
                withLine(text, mutant.line(), shown.get(), lineVariant),
                "its changed line, `" + shown.get() + "`"));
      }
    }

    var variants = temp.resolve("variants/shapes");
    Files.createDirectories(variants);
    for (var claim : claims) {
      Files.writeString(variants.resolve(claim.variant() + ".java"), claim.source());
    }
    // With the fixture itself, and the other class whose static a condition reads.
    var files = new ArrayList<Path>(List.of(FIXTURE.resolve("shapes/Shapes.java"), OTHER));
    claims.forEach(claim -> files.add(variants.resolve(claim.variant() + ".java")));
    var claimed = compile(files, "claimed");

    try (var loader = new URLClassLoader(new java.net.URL[] {claimed.toUri().toURL()})) {
      for (var claim : claims) {
        var said = loader.loadClass("shapes." + claim.variant());
        var mutated =
            new OneClass(CLASS, mutations.apply(model, claim.mutant()), original).loadClass(CLASS);
        differs(claim.mutant().methodName(), said, mutated)
            .ifPresent(
                input ->
                    failures.add(
                        describe(claim.mutant(), claim.advice())
                            + "\n  "
                            + claim.what()
                            + ": but at "
                            + input
                            + " the mutant does otherwise"));
      }
    }

    assertThat(claims).as("the claims checked").hasSizeGreaterThan(60);
    assertThat(claims.stream().map(claim -> claim.mutant().methodName()).distinct())
        .as("the shapes with a claim checked")
        .hasSizeGreaterThan(20);
    // A constant the source names still gets its changed line, quoted as the source has it.
    assertThat(claims)
        .extracting(Claim::what)
        .contains("its changed line, `if (b < 0 || a >= EDGE) {`");
    assertThat(failures).as("cards that say something their mutant doesn't do").isEmpty();
  }

  /// A mutant skipped as equivalent behaves as the code does on every input: a skip rule that's
  /// wrong hides a mutant a test could catch, which is worse than a card that's vague.
  @Test
  void everyEquivalentSkipIsEquivalent() throws Exception {
    var original = compile(List.of(FIXTURE.resolve("shapes/Shapes.java"), OTHER), "original");
    var model = ClassFile.of().parse(Files.readAllBytes(original.resolve("shapes/Shapes.class")));
    var mutations =
        new Mutations(
            ClassFile.of(),
            new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));
    var equivalent =
        mutations.find(model).stream()
            .filter(mutant -> mutant.skipped().orElse("").startsWith("equivalent:"))
            .filter(mutant -> !HELPERS.contains(mutant.methodName()))
            .toList();
    var code =
        new OneClass(CLASS, Files.readAllBytes(original.resolve("shapes/Shapes.class")), original)
            .loadClass(CLASS);
    var failures = new ArrayList<String>();
    for (var mutant : equivalent) {
      var mutated = new OneClass(CLASS, mutations.apply(model, mutant), original).loadClass(CLASS);
      differs(mutant.methodName(), code, mutated)
          .ifPresent(
              input -> failures.add(mutant + " (" + mutant.skipped().get() + ") at " + input));
    }

    assertThat(equivalent)
        .as("the `||` shapes whose first test the second covers")
        .filteredOn(mutant -> mutant.skipped().get().contains("`||`"))
        .extracting(Mutant::methodName, Mutant::operator, Mutant::replacement)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                "impliedByACall", BuiltIn.REMOVE_CONDITIONAL, "never jump"),
            org.assertj.core.groups.Tuple.tuple(
                "impliedComparison", BuiltIn.REMOVE_CONDITIONAL, "never jump"),
            org.assertj.core.groups.Tuple.tuple("impliedComparison", BuiltIn.BOUNDARY, "IF_ICMPGE"),
            org.assertj.core.groups.Tuple.tuple(
                "impliedComparison", BuiltIn.OTHER_COMPARISON, "IF_ICMPEQ"));
    assertThat(equivalent)
        .as("the edges where both sides give the same value")
        .filteredOn(mutant -> mutant.skipped().get().contains("same value at the edge"))
        .extracting(Mutant::methodName)
        .contains("clampLow", "clampZero", "clampLong", "raiseTo")
        .doesNotContain("notAClamp", "clampToSomethingElse");
    assertThat(equivalent)
        .as("comparisons of a size or length that differ only below zero")
        .filteredOn(mutant -> mutant.skipped().get().equals(Comparisons.NEVER_NEGATIVE))
        .extracting(Mutant::methodName)
        .contains("lengthNotZero", "sizeAboveZero");
    assertThat(failures).as("mutants skipped as equivalent that aren't").isEmpty();
  }

  /// Only numbers are ordered: `==` on two `boolean`s gets only its opposite. A `float`'s
  /// comparison gets no change that `NaN` would make differ somewhere the card doesn't say.
  @Test
  void aComparisonChangesOnlyWhatItsOperandsAllow() throws Exception {
    var original = compile(List.of(FIXTURE.resolve("shapes/Shapes.java"), OTHER), "original");
    var model = ClassFile.of().parse(Files.readAllBytes(original.resolve("shapes/Shapes.class")));
    var found =
        new Mutations(
                ClassFile.of(),
                new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()))
            .find(model);

    assertThat(changes(found, "booleansEqual", BuiltIn.COMPARISON)).isEmpty();
    assertThat(changes(found, "booleansEqual", BuiltIn.OTHER_COMPARISON))
        .containsExactly("IF_ICMPEQ");
    assertThat(changes(found, "equalLongs", BuiltIn.COMPARISON)).containsExactly("IFLT", "IFGT");
    assertThat(changes(found, "equalsAnExpression", BuiltIn.COMPARISON))
        .containsExactly("IF_ICMPLT", "IF_ICMPGT");
    // `x < b` on floats: `x <= b` stays, `x != b` would differ for NaN too.
    assertThat(changes(found, "belowAFloat", BuiltIn.BOUNDARY)).containsExactly("IFGT");
    assertThat(changes(found, "belowAFloat", BuiltIn.COMPARISON)).isEmpty();
    assertThat(changes(found, "equalFloats", BuiltIn.COMPARISON)).isEmpty();
    assertThat(changes(found, "equalFloats", BuiltIn.OTHER_COMPARISON)).containsExactly("IFEQ");
  }

  /// The jumps `operator` changes the comparison of `x` (or `a`) with `b` in `method` to.
  private static List<String> changes(List<Mutant> found, String method, BuiltIn operator) {
    return found.stream()
        .filter(mutant -> mutant.methodName().equals(method) && mutant.operator() == operator)
        .filter(mutant -> mutant.operands().stream().anyMatch(operand -> operand.contains("b")))
        .map(Mutant::replacement)
        .toList();
  }

  /// The source with the card's claim written into the mutant's method, as class `variant`.
  private static Optional<String> claim(String text, Mutant mutant, Advice advice, String variant) {
    Optional<String[]> edit =
        switch ((BuiltIn) mutant.operator()) {
          case REMOVE_CONDITIONAL ->
              match(ALWAYS, advice.change())
                  .map(
                      found ->
                          new String[] {
                            found.group("condition"),
                            "Boolean." + found.group("value").toUpperCase(java.util.Locale.ROOT)
                          });
          case BOUNDARY, COMPARISON, OTHER_COMPARISON ->
              match(REVERSED, advice.headline())
                  .map(found -> new String[] {found.group("condition"), "!(%s)"})
                  .or(() -> boundary(mutant, advice));
          default -> Optional.empty();
        };
    return edit.flatMap(found -> replaceIn(text, mutant, found[0], found[1], variant));
  }

  /// A comparison's headline, that the mutant differs only where `a > b`, written in: the condition
  /// becomes the mutant's there and stays as it is elsewhere, which must behave as the mutant does.
  /// A headline naming the wrong side fails, as the inputs on the right one then differ.
  private static Optional<String> headline(
      String text, Mutant mutant, Advice advice, String variant) {
    if (mutant.operator() == BuiltIn.REMOVE_CONDITIONAL) {
      return Optional.empty();
    }
    var where = match(WHERE, advice.headline());
    return where.flatMap(
        found ->
            boundary(mutant, advice)
                .flatMap(
                    edit ->
                        replaceIn(
                            text,
                            mutant,
                            edit[0],
                            "((%s) ? (%s) : (%%s))".formatted(found.group("where"), edit[1]),
                            variant)));
  }

  /// `a >= b` to `a > b`: the comparison as the card says it's written, and as it says it becomes.
  private static Optional<String[]> boundary(Mutant mutant, Advice advice) {
    var operands = mutant.operands();
    if (operands.size() != 2 || advice.before().isEmpty() || advice.after().isEmpty()) {
      return Optional.empty();
    }
    var before = "%s %s %s".formatted(operands.get(0), advice.before().get(), operands.get(1));
    var after = "%s %s %s".formatted(operands.get(0), advice.after().get(), operands.get(1));
    return Optional.of(new String[] {before, after});
  }

  private static Optional<java.util.regex.Matcher> match(Pattern pattern, String text) {
    var found = pattern.matcher(text);
    return found.find() ? Optional.of(found) : Optional.empty();
  }

  /// The source with `condition` replaced by `replacement`, in which `%s` stands for the condition
  /// itself: where it's found once, as whole words, on the mutant's line, or else in its method.
  private static Optional<String> replaceIn(
      String source, Mutant mutant, String condition, String replacement, String variant) {
    var text = withConstantsAsValues(source);
    var written = condition;
    var words = Pattern.compile("(?<!\\w)" + Pattern.quote(written) + "(?!\\w)");
    var line = text.lines().toList().get(mutant.line() - 1);
    int lineStart = text.indexOf(line + "\n");
    var onLine = once(words, line);
    if (onLine.isPresent() && lineStart >= 0) {
      int at = lineStart + onLine.getAsInt();
      return Optional.of(
          renamed(
              text.substring(0, at)
                  + replacement.replace("%s", written)
                  + text.substring(at + written.length()),
              variant));
    }
    return replaceInMethod(text, mutant, words, written, replacement, variant);
  }

  private static Optional<String> replaceInMethod(
      String text,
      Mutant mutant,
      Pattern words,
      String written,
      String replacement,
      String variant) {
    var method = mutant.methodName();
    var start = text.indexOf(" " + method + "(int a, int b)");
    if (start < 0) {
      start = text.indexOf(" " + method + "(");
    }
    var end = text.indexOf("\n  static ", start + 1);
    end = end < 0 ? text.lastIndexOf('}') : end;
    var body = text.substring(start, end);
    var found = once(words, body);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    int at = found.getAsInt();
    var changed =
        body.substring(0, at)
            + replacement.replace("%s", written)
            + body.substring(at + written.length());
    return Optional.of(renamed(text.substring(0, start) + changed + text.substring(end), variant));
  }

  /// The source with line `number` (from 1) replaced by `shown`, kept at its indentation. Its
  /// `true` and `false` are written `Boolean.TRUE` and `Boolean.FALSE`, the same values: javac
  /// won't take a constant as a `case` guard, and folds `while (true)` into a loop with no end.
  private static String withLine(String text, int number, String shown, String variant) {
    var lines = new ArrayList<>(text.lines().toList());
    var old = lines.get(number - 1);
    var boxed =
        LITERAL
            .matcher(shown)
            .replaceAll(found -> "Boolean." + found.group().toUpperCase(java.util.Locale.ROOT));
    lines.set(number - 1, old.substring(0, old.length() - old.stripLeading().length()) + boxed);
    return renamed(String.join("\n", lines) + "\n", variant);
  }

  /// `static final int EDGE = 3;` and each use of `EDGE` as `3`, as javac inlines it and a card
  /// quotes it, and `1L` as `1`: the same code, so a claim about `a > 3` can be found and written
  /// in. Line numbers stay as they were.
  private static String withConstantsAsValues(String text) {
    var constants = CONSTANT.matcher(text);
    var resolved = text;
    while (constants.find()) {
      var use = Pattern.compile("(?<![\\w.])" + constants.group("name") + "(?![\\w(])(?! = )");
      resolved = use.matcher(resolved).replaceAll(constants.group("value"));
    }
    // `1L` is a long `1` in the bytecode, as a widened int `1` is: a card can't tell which the
    // source wrote, and quotes `1`. The same code either way.
    return LONG_LITERAL.matcher(resolved).replaceAll("$1");
  }

  /// Where `words` is in `text`, if it's there exactly once.
  private static java.util.OptionalInt once(Pattern words, String text) {
    var found = words.matcher(text);
    if (!found.find()) {
      return java.util.OptionalInt.empty();
    }
    int at = found.start();
    return found.find() ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(at);
  }

  private static String renamed(String text, String variant) {
    return text.replace("class Shapes ", "class " + variant + " ")
        .replace("private Shapes()", "private " + variant + "()");
  }

  /// The first input on which the two classes' `method` differ, if any: a value, or what it threw.
  private static Optional<String> differs(String method, Class<?> said, Class<?> mutated)
      throws ReflectiveOperationException {
    var expected = method(said, method);
    var actual = method(mutated, method);
    for (int a = LOW; a <= HIGH; a++) {
      for (int b = LOW; b <= HIGH; b++) {
        var claimed = outcome(expected, a, b);
        var done = outcome(actual, a, b);
        if (!claimed.equals(done)) {
          return Optional.of(
              "a = %d, b = %d (the card's source gives %s, the mutant %s)"
                  .formatted(a, b, claimed, done));
        }
      }
    }
    return Optional.empty();
  }

  private static Method method(Class<?> owner, String name) throws NoSuchMethodException {
    var method = owner.getDeclaredMethod(name, int.class, int.class);
    method.setAccessible(true);
    return method;
  }

  private static String outcome(Method method, int a, int b) throws IllegalAccessException {
    try {
      return Objects.toString(method.invoke(null, a, b));
    } catch (InvocationTargetException thrown) {
      return "threw " + thrown.getCause().getClass().getSimpleName();
    }
  }

  private static String describe(Mutant mutant, Advice advice) {
    return "%s %s %s -> %s %s: %s; %s"
        .formatted(
            mutant.methodName(),
            mutant.operator().id(),
            mutant.original(),
            mutant.replacement(),
            mutant.operands(),
            advice.change(),
            advice.headline());
  }

  /// The files compiled with their local names, into a new directory under `temp`.
  private Path compile(List<Path> files, String name) throws IOException {
    var out = Files.createDirectories(temp.resolve(name));
    var args = new ArrayList<>(List.of("-g", "-d", out.toString()));
    files.forEach(file -> args.add(file.toString()));
    var errors = new java.io.ByteArrayOutputStream();
    int status =
        Objects.requireNonNull(ToolProvider.getSystemJavaCompiler())
            .run(null, null, errors, args.toArray(String[]::new));
    assertThat(status).as("javac: %s", errors).isZero();
    return out;
  }

  /// The mutated class from its bytes, and the fixture's other classes from where they were
  /// compiled, all in one loader: they share a package, and its package-private members.
  private static final class OneClass extends ClassLoader {
    private final String name;
    private final byte[] bytes;
    private final Path classes;

    OneClass(String name, byte[] bytes, Path classes) {
      super(CardTruthTest.class.getClassLoader());
      this.name = name;
      this.bytes = bytes.clone();
      this.classes = classes;
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
      if (!className.startsWith("shapes.")) {
        return super.loadClass(className, resolve);
      }
      synchronized (getClassLoadingLock(className)) {
        var loaded = findLoadedClass(className);
        if (loaded != null) {
          return loaded;
        }
        var defined = className.equals(name) ? bytes : compiled(className);
        return defineClass(className, defined, 0, defined.length);
      }
    }

    private byte[] compiled(String className) throws ClassNotFoundException {
      try {
        return Files.readAllBytes(classes.resolve(className.replace('.', '/') + ".class"));
      } catch (IOException e) {
        throw new ClassNotFoundException(className, e);
      }
    }
  }
}
