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

  /// The predicates the conditions call: not conditions of their own.
  private static final Set<String> HELPERS = Set.of("even", "small");

  private static final Set<BuiltIn> CONDITIONS =
      Set.of(BuiltIn.BOUNDARY, BuiltIn.REMOVE_CONDITIONAL, BuiltIn.NEGATE_CONDITIONAL);

  private static final Pattern ALWAYS =
      Pattern.compile("`(?<condition>[^`]+)` is always (?<value>true|false)");
  private static final Pattern REVERSED = Pattern.compile("with `(?<condition>[^`]+)` reversed");

  /// `Shapes.even(a)`, as the operands name a call, is `even(a)` in the source.
  private static final Pattern OWNER = Pattern.compile("\\b[A-Z]\\w*\\.(?=\\w+\\()");

  private static final int LOW = -4;
  private static final int HIGH = 5;

  @TempDir Path temp;

  /// One mutant's card, and the source that says what the card says it does.
  private record Claim(Mutant mutant, Advice advice, String variant, String source) {}

  @Test
  void everyConditionsCardSaysWhatItsMutantDoes() throws Exception {
    var original = compile(List.of(FIXTURE.resolve("shapes/Shapes.java")), "original");
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
              source -> claims.add(new Claim(mutant, advice, variant, source)),
              () ->
                  failures.add(describe(mutant, advice) + "\n  its condition isn't in the source"));
    }

    var variants = temp.resolve("variants/shapes");
    Files.createDirectories(variants);
    for (var claim : claims) {
      Files.writeString(variants.resolve(claim.variant() + ".java"), claim.source());
    }
    var claimed =
        compile(
            claims.stream().map(claim -> variants.resolve(claim.variant() + ".java")).toList(),
            "claimed");

    try (var loader = new URLClassLoader(new java.net.URL[] {claimed.toUri().toURL()})) {
      for (var claim : claims) {
        var said = loader.loadClass("shapes." + claim.variant());
        var mutated = new OneClass(CLASS, mutations.apply(model, claim.mutant())).loadClass(CLASS);
        differs(claim.mutant().methodName(), said, mutated)
            .ifPresent(
                input ->
                    failures.add(
                        describe(claim.mutant(), claim.advice())
                            + "\n  but at "
                            + input
                            + " the mutant does otherwise"));
      }
    }

    assertThat(claims).as("the claims checked").hasSizeGreaterThan(60);
    assertThat(claims.stream().map(claim -> claim.mutant().methodName()).distinct())
        .as("the shapes with a claim checked")
        .hasSizeGreaterThan(20);
    assertThat(failures).as("cards that say something their mutant doesn't do").isEmpty();
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
          case NEGATE_CONDITIONAL ->
              match(REVERSED, advice.headline())
                  .map(found -> new String[] {found.group("condition"), "!(%s)"});
          case BOUNDARY -> boundary(mutant, advice);
          default -> Optional.empty();
        };
    return edit.flatMap(found -> replaceIn(text, mutant, found[0], found[1], variant));
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
      String text, Mutant mutant, String condition, String replacement, String variant) {
    var written = OWNER.matcher(condition).replaceAll("");
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

  /// One class from bytes, and everything else from the loader that loaded the test.
  private static final class OneClass extends ClassLoader {
    private final String name;
    private final byte[] bytes;

    OneClass(String name, byte[] bytes) {
      super(CardTruthTest.class.getClassLoader());
      this.name = name;
      this.bytes = bytes.clone();
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
      if (className.equals(name)) {
        synchronized (getClassLoadingLock(className)) {
          var loaded = findLoadedClass(className);
          return loaded != null ? loaded : defineClass(className, bytes, 0, bytes.length);
        }
      }
      return super.loadClass(className, resolve);
    }
  }
}
