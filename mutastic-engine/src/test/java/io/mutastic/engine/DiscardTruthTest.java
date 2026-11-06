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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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

  @Test
  void aCallIsRemovedOnlyWhereItsResultIsThrownAway() throws Exception {
    var calls = new LinkedHashMap<String, List<String>>();
    for (Mutant mutant : mutations.find(model())) {
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
    var model = model();
    var sources = new SourceView(FIXTURE, List.of(FIXTURE));
    var lines = Files.readAllLines(SOURCE);
    var mutants =
        mutations.find(model).stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
    var variants = new ArrayList<Javac.Source>();
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
      variants.add(withoutLine(lines, mutant.line()));
    }
    var loader = Javac.loader(Javac.compile(variants, List.of()));

    var code = load(model, Optional.empty());
    for (int at = 0; at < mutants.size(); at++) {
      var mutant = mutants.get(at);
      var name = mutant.methodName();
      var mutated = load(model, Optional.of(mutant));
      var said = loader.loadClass(variants.get(at).className());
      if (!outcomes(name, said).equals(outcomes(name, mutated))) {
        failures.add(describe(mutant, cards.get(at)) + "\n  the mutant isn't the line removed");
      }
      if (outcomes(name, code).equals(outcomes(name, mutated))) {
        failures.add(describe(mutant, cards.get(at)) + "\n  changes nothing");
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

  private static ClassModel model() throws IOException {
    var original = Javac.compile(List.of(Javac.Source.read(SOURCE, CLASS)), List.of());
    return ClassFile.of().parse(original.get(CLASS));
  }

  private Class<?> load(ClassModel model, Optional<Mutant> mutant) throws ClassNotFoundException {
    var bytes =
        mutant.isPresent()
            ? mutations.apply(model, mutant.get())
            : ClassFile.of().transformClass(model, java.lang.classfile.ClassTransform.ACCEPT_ALL);
    return new OneClass(bytes).loadClass(CLASS);
  }

  /// The source with line `number` (from 1) left blank, as a class of its own.
  private static Javac.Source withoutLine(List<String> lines, int number) {
    var changed = new ArrayList<>(lines);
    changed.set(number - 1, "");
    var text = String.join("\n", changed);
    var name = Javac.nameFor("Discards", text);
    return new Javac.Source(
        "discards." + name,
        text.replace("final class Discards ", "final class " + name + " ")
            .replace("private Discards()", "private " + name + "()"));
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
