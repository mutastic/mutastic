package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.Advice;
import io.mutastic.report.SourceView;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Each card a changed return gets says what its mutant does, checked against Java itself.
///
/// The fixture has a method for each way javac compiles a return of each type. For every mutant,
/// the card's changed line (`return false;`) is written in place of the return, and that source,
/// compiled, must behave as the mutant does on every input. The value a boolean's card says the
/// method returns today (`returning `true``) must be one it returns, and the mutant must change
/// something: a change that can't is skipped as equivalent, and must be.
class ReturnTruthTest {
  private static final Path FIXTURE = Path.of("src/test/resources/truth");
  private static final Path SOURCE = FIXTURE.resolve("returns/Returns.java");
  private static final String CLASS = "returns.Returns";
  private static final Pattern TODAY = Pattern.compile("returning `(?<value>[^`]+)`");
  private static final int LOW = -4;
  private static final int HIGH = 5;

  private final Mutations mutations =
      new Mutations(
          ClassFile.of(),
          new Policy(
              List.of(), List.of(), List.of(), List.of(BuiltIn.RETURN_VALUE.id()), List.of()));

  @TempDir Path temp;

  @Test
  void eachShapeGetsTheValueItMostPlausiblyGetsWrong() throws Exception {
    var changes = new LinkedHashMap<String, List<String>>();
    for (Mutant mutant : mutations.find(model(compile(List.of(SOURCE), "original")))) {
      changes
          .computeIfAbsent(mutant.methodName(), name -> new ArrayList<>())
          .add(mutant.replacement() + mutant.skipped().map(why -> " (" + why + ")").orElse(""));
    }

    assertThat(changes)
        .containsEntry("literalTrue", List.of("return false"))
        .containsEntry("literalFalse", List.of("return true"))
        .containsEntry("comparison", List.of("return false", "return true"))
        .containsEntry("early", List.of("return true", "return false", "return true"))
        .containsEntry("boxedTrue", List.of("return false"))
        .containsEntry("boxedConstant", List.of("return true"))
        .containsEntry("boxedComputed", List.of("return false", "return true"))
        .containsEntry("zero", List.of("return 1"))
        .containsEntry("sum", List.of("return 0"))
        .containsEntry("longZero", List.of("return 1L"))
        .containsEntry("product", List.of("return 0L"))
        .containsEntry("doubleZero", List.of("return 1.0"))
        .containsEntry("scaled", List.of("return 0.0f"))
        .containsEntry("boxedZero", List.of("return 1"))
        .containsEntry("boxedLong", List.of("return 0L"))
        .containsEntry("firstOf", List.of("return 0"))
        .containsEntry("flagOf", List.of("return false", "return true"))
        .containsEntry("character", List.of("return null"))
        .containsEntry("text", List.of("return \"\""))
        .containsEntry("nullText", List.of("return \"\""))
        .containsEntry(
            "emptyText", List.of("return \"\" (equivalent: already returns an empty string)"))
        .containsEntry("positive", List.of("return Optional.empty()"))
        .containsEntry(
            "none",
            List.of("return Optional.empty() (equivalent: already returns an empty Optional)"))
        .containsEntry("pair", List.of("return List.of()"))
        .containsEntry(
            "noList", List.of("return List.of() (equivalent: already returns an empty list)"))
        .containsEntry(
            "emptyList", List.of("return List.of() (equivalent: already returns an empty list)"))
        .containsEntry("single", List.of("return Set.of()"))
        .containsEntry(
            "emptySet", List.of("return Set.of() (equivalent: already returns an empty set)"))
        .containsEntry(
            "noMap", List.of("return Map.of() (equivalent: already returns an empty map)"))
        .containsEntry("collection", List.of("return List.of()"))
        .containsEntry("iterable", List.of("return List.of()"))
        .containsEntry("stream", List.of("return Stream.empty()"))
        .containsEntry(
            "noStream",
            List.of("return Stream.empty() (equivalent: already returns an empty stream)"))
        .containsEntry("object", List.of("return null"))
        .containsEntry("nothing", List.of("return null (equivalent: already returns null)"))
        .containsEntry("array", List.of("return null"));
  }

  @Test
  void everyReturnsCardSaysWhatItsMutantDoes() throws Exception {
    var original = compile(List.of(SOURCE), "original");
    var model = model(original);
    var sources = new SourceView(FIXTURE, List.of(FIXTURE));
    var text = Files.readString(SOURCE);
    var mutants =
        mutations.find(model).stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
    var variants = Files.createDirectories(temp.resolve("variants/returns"));
    var files = new ArrayList<Path>(List.of(SOURCE));
    var failures = new ArrayList<String>();
    var cards = new ArrayList<Advice>();
    for (var mutant : mutants) {
      var advice = sources.advice(mutant);
      cards.add(advice);
      var shown = sources.mutatedLine(mutant, advice);
      if (shown.isEmpty()) {
        failures.add(describe(mutant, advice) + "\n  has no changed line");
        continue;
      }
      var variant = variants.resolve("Returns" + (cards.size() - 1) + ".java");
      Files.writeString(variant, withLine(text, mutant.line(), shown.get(), variant));
      files.add(variant);
    }
    var claimed = compile(files, "claimed");

    var code = load(model, Optional.empty());
    try (var loader = new URLClassLoader(new java.net.URL[] {claimed.toUri().toURL()})) {
      for (int at = 0; at < mutants.size(); at++) {
        var mutant = mutants.get(at);
        var advice = cards.get(at);
        var mutated = load(model, Optional.of(mutant));
        var said = loader.loadClass("returns.Returns" + at);
        var name = mutant.methodName();
        differs(name, said, mutated)
            .ifPresent(
                input ->
                    failures.add(
                        describe(mutant, advice) + "\n  its changed line: but at " + input));
        if (outcomes(name, code).equals(outcomes(name, mutated))) {
          failures.add(describe(mutant, advice) + "\n  changes nothing, so it's equivalent");
        }
        var today = today(advice);
        if (today.isPresent() && !changesToday(name, code, mutated, today.get())) {
          failures.add(
              describe(mutant, advice)
                  + "\n  no input returns `"
                  + today.get()
                  + "` that the mutant changes");
        }
      }
    }

    assertThat(mutants).as("the returns checked").hasSizeGreaterThan(30);
    assertThat(failures).as("cards that say something their mutant doesn't do").isEmpty();
  }

  /// A return skipped as equivalent behaves as the code does on every input.
  @Test
  void everyEquivalentReturnIsEquivalent() throws Exception {
    var original = compile(List.of(SOURCE), "original");
    var model = model(original);
    var code = load(model, Optional.empty());
    var equivalent =
        mutations.find(model).stream()
            .filter(mutant -> mutant.skipped().orElse("").startsWith("equivalent:"))
            .toList();
    var failures = new ArrayList<String>();
    for (var mutant : equivalent) {
      var mutated = load(model, Optional.of(mutant));
      differs(mutant.methodName(), code, mutated)
          .ifPresent(input -> failures.add(mutant + " at " + input));
    }

    assertThat(equivalent).hasSize(8);
    assertThat(failures).as("returns skipped as equivalent that aren't").isEmpty();
  }

  /// The value a card says the method returns today, for a change that takes it away.
  private static Optional<String> today(Advice advice) {
    var found = TODAY.matcher(advice.headline());
    return found.find() ? Optional.of(found.group("value")) : Optional.empty();
  }

  /// Whether some input gives `value` today and something else with the mutant.
  private static boolean changesToday(String name, Class<?> code, Class<?> mutated, String value)
      throws ReflectiveOperationException {
    var before = outcomes(name, code);
    var after = outcomes(name, mutated);
    var expected = value.replaceAll("[Lf]$", "");
    for (int at = 0; at < before.size(); at++) {
      if (before.get(at).equals(expected) && !after.get(at).equals(expected)) {
        return true;
      }
    }
    return false;
  }

  private static ClassModel model(Path classes) throws IOException {
    return ClassFile.of().parse(Files.readAllBytes(classes.resolve("returns/Returns.class")));
  }

  private Class<?> load(ClassModel model, Optional<Mutant> mutant) throws ClassNotFoundException {
    var bytes =
        mutant.isPresent()
            ? mutations.apply(model, mutant.get())
            : ClassFile.of().transformClass(model, java.lang.classfile.ClassTransform.ACCEPT_ALL);
    return new OneClass(bytes).loadClass(CLASS);
  }

  /// The line `number` (from 1) replaced by `shown`, kept at its indentation, as class `variant`.
  private static String withLine(String text, int number, String shown, Path variant) {
    var lines = new ArrayList<>(text.lines().toList());
    var old = lines.get(number - 1);
    lines.set(number - 1, old.substring(0, old.length() - old.stripLeading().length()) + shown);
    var name = variant.getFileName().toString().replace(".java", "");
    return String.join("\n", lines)
        .replace("final class Returns ", "final class " + name + " ")
        .replace("private Returns()", "private " + name + "()");
  }

  /// The first input on which the two classes' `method` differ, if any.
  private static Optional<String> differs(String method, Class<?> said, Class<?> mutated)
      throws ReflectiveOperationException {
    var expected = outcomes(method, said);
    var actual = outcomes(method, mutated);
    for (int at = 0; at < expected.size(); at++) {
      if (!expected.get(at).equals(actual.get(at))) {
        return Optional.of(
            "input %d (the card's source gives %s, the mutant %s)"
                .formatted(at, expected.get(at), actual.get(at)));
      }
    }
    return Optional.empty();
  }

  /// What `method` gives for each pair of inputs, as text: a stream's elements, an array's.
  private static List<String> outcomes(String name, Class<?> owner)
      throws ReflectiveOperationException {
    Method method = owner.getDeclaredMethod(name, int.class, int.class);
    method.setAccessible(true);
    var outcomes = new ArrayList<String>();
    for (int a = LOW; a <= HIGH; a++) {
      for (int b = LOW; b <= HIGH; b++) {
        outcomes.add(outcome(method, a, b));
      }
    }
    return outcomes;
  }

  private static String outcome(Method method, int a, int b) throws IllegalAccessException {
    try {
      return switch (method.invoke(null, a, b)) {
        case Stream<?> stream -> stream.toList().toString();
        case int[] array -> Arrays.toString(array);
        case null -> "null";
        case Object value -> value.toString();
      };
    } catch (InvocationTargetException thrown) {
      return "threw " + thrown.getCause().getClass().getSimpleName();
    }
  }

  private static String describe(Mutant mutant, Advice advice) {
    return "%s line %d %s: %s; %s; %s"
        .formatted(
            mutant.methodName(),
            mutant.line(),
            mutant.replacement(),
            advice.change(),
            advice.headline(),
            advice.fix());
  }

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

  /// The fixture's class from the given bytes, in a loader of its own.
  private static final class OneClass extends ClassLoader {
    private final byte[] bytes;

    OneClass(byte[] bytes) {
      super(ReturnTruthTest.class.getClassLoader());
      this.bytes = bytes.clone();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      if (!name.equals(CLASS)) {
        throw new ClassNotFoundException(name);
      }
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
