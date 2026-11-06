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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Each card a removed call whose result was thrown away gets says what its mutant does, checked
/// against Java itself: "the call to `add()` is removed" takes the call's line out of the source,
/// and that source, compiled, must behave as the mutant does on every input.
class DiscardTruthTest {
  private static final Path FIXTURE = Path.of("src/test/resources/truth");
  private static final Path SOURCE = FIXTURE.resolve("discards/Discards.java");
  private static final String CLASS = "discards.Discards";
  private static final int LOW = -4;
  private static final int HIGH = 5;

  private final Mutations mutations =
      new Mutations(
          ClassFile.of(),
          new Policy(
              List.of(),
              List.of(),
              List.of(),
              List.of(BuiltIn.REMOVE_DISCARDED_CALL.id()),
              List.of()));

  @TempDir Path temp;

  @Test
  void aCallIsRemovedOnlyWhereItsResultIsThrownAway() throws Exception {
    var calls = new LinkedHashMap<String, List<String>>();
    for (Mutant mutant : mutations.find(model(compile(List.of(SOURCE), "original")))) {
      calls
          .computeIfAbsent(mutant.methodName(), name -> new ArrayList<>())
          .add(called(mutant) + mutant.skipped().map(why -> " (" + why + ")").orElse(""));
    }

    assertThat(calls)
        .containsEntry("added", List.of("add", "add"))
        .containsEntry("appended", List.of("append", "append"))
        .containsEntry("put", List.of("put", "put"))
        .containsEntry("removed", List.of("remove"))
        .containsEntry("counted", List.of("incrementAndGet", "addAndGet"))
        .containsEntry("parsed", List.of("parseInt"))
        .containsEntry("checked", List.of("orElseThrow"))
        .containsEntry(
            "required", List.of("requireNonNull (arid: only checks the value isn't null)"))
        .doesNotContainKey("used");
  }

  @Test
  void everyRemovedCallsCardSaysWhatItsMutantDoes() throws Exception {
    var original = compile(List.of(SOURCE), "original");
    var model = model(original);
    var sources = new SourceView(FIXTURE, List.of(FIXTURE));
    var lines = Files.readAllLines(SOURCE);
    var mutants =
        mutations.find(model).stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
    var variants = Files.createDirectories(temp.resolve("variants/discards"));
    var files = new ArrayList<Path>(List.of(SOURCE));
    var failures = new ArrayList<String>();
    var cards = new ArrayList<Advice>();
    for (var mutant : mutants) {
      var advice = sources.advice(mutant);
      cards.add(advice);
      var name = called(mutant);
      var line = lines.get(mutant.line() - 1);
      if (!advice.change().equals("the call to `%s()` is removed".formatted(name))
          || !line.contains(name + "(")) {
        failures.add(describe(mutant, advice) + "\n  doesn't name the call on its line");
      }
      var variant = variants.resolve("Discards" + (cards.size() - 1) + ".java");
      Files.writeString(variant, withoutLine(lines, mutant.line(), variant));
      files.add(variant);
    }
    var claimed = compile(files, "claimed");

    var code = load(model, Optional.empty());
    try (var loader = new URLClassLoader(new java.net.URL[] {claimed.toUri().toURL()})) {
      for (int at = 0; at < mutants.size(); at++) {
        var mutant = mutants.get(at);
        var name = mutant.methodName();
        var mutated = load(model, Optional.of(mutant));
        var said = loader.loadClass("discards.Discards" + at);
        if (!outcomes(name, said).equals(outcomes(name, mutated))) {
          failures.add(describe(mutant, cards.get(at)) + "\n  the mutant isn't the line removed");
        }
        if (outcomes(name, code).equals(outcomes(name, mutated))) {
          failures.add(describe(mutant, cards.get(at)) + "\n  changes nothing");
        }
      }
    }

    assertThat(mutants).as("the calls checked").hasSize(11);
    assertThat(failures).as("cards that say something their mutant doesn't do").isEmpty();
  }

  /// `add` for `INVOKEVIRTUAL java/util/ArrayList.add(Ljava/lang/Object;)Z`.
  private static String called(Mutant mutant) {
    var call = mutant.original();
    var target = call.substring(call.indexOf(' ') + 1, call.indexOf('('));
    return target.substring(target.lastIndexOf('.') + 1);
  }

  private static ClassModel model(Path classes) throws IOException {
    return ClassFile.of().parse(Files.readAllBytes(classes.resolve("discards/Discards.class")));
  }

  private Class<?> load(ClassModel model, Optional<Mutant> mutant) throws ClassNotFoundException {
    var bytes =
        mutant.isPresent()
            ? mutations.apply(model, mutant.get())
            : ClassFile.of().transformClass(model, java.lang.classfile.ClassTransform.ACCEPT_ALL);
    return new OneClass(bytes).loadClass(CLASS);
  }

  /// The source with line `number` (from 1) left blank, as class `variant`.
  private static String withoutLine(List<String> lines, int number, Path variant) {
    var changed = new ArrayList<>(lines);
    changed.set(number - 1, "");
    var name = variant.getFileName().toString().replace(".java", "");
    return String.join("\n", changed)
        .replace("final class Discards ", "final class " + name + " ")
        .replace("private Discards()", "private " + name + "()");
  }

  /// What `name` gives for each pair of inputs, as text, or what it threw.
  private static List<String> outcomes(String name, Class<?> owner)
      throws ReflectiveOperationException {
    Method method = owner.getDeclaredMethod(name, int.class, int.class);
    method.setAccessible(true);
    var outcomes = new ArrayList<String>();
    for (int a = LOW; a <= HIGH; a++) {
      for (int b = LOW; b <= HIGH; b++) {
        try {
          outcomes.add(String.valueOf(method.invoke(null, a, b)));
        } catch (InvocationTargetException thrown) {
          outcomes.add("threw " + thrown.getCause().getClass().getSimpleName());
        }
      }
    }
    return outcomes;
  }

  private static String describe(Mutant mutant, Advice advice) {
    return "%s line %d %s: %s; %s"
        .formatted(
            mutant.methodName(), mutant.line(), mutant.original(), advice.change(), advice.fix());
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
      super(DiscardTruthTest.class.getClassLoader());
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
