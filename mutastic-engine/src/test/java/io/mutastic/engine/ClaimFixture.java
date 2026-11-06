package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// A fixture class that writes each mutant's claim out as source beside its method: `xClaim` is `x`
/// as its card says the mutant makes it, and `xClaim_name` if the method has one change for each of
/// several names. Every method takes two of the fixture's [Inputs]. It's compiled with [Javac],
/// once however many mutants it has, as truth tests run for every mutant that reaches them.
final class ClaimFixture {
  static final Path ROOT = Path.of("src/test/resources/truth");

  /// What a fixture's methods take two of, and the values each is called with. Every numeric shape
  /// is written as a method that takes two `int`s: `(long) a * 3L`, `(char) a == 'x'`.
  record Inputs(Class<?> type, String descriptor, List<Object> values) {
    static final Inputs TEXTS =
        new Inputs(
            String.class,
            "(Ljava/lang/String;Ljava/lang/String;)",
            List.of(
                "a", "b", "aa", "ab", "ba", "aba", " a", "a ", " a ", "A", "aB", "1", "abcde",
                "<b>"));

    /// Either side of `0`, of `1`, of the fixtures' constants, of a letter, and at each end of
    /// `int`, so that a change near any of them shows and an overflow is reached.
    static final Inputs NUMBERS =
        new Inputs(
            int.class,
            "(II)",
            List.of(
                Integer.MIN_VALUE,
                -3,
                -1,
                0,
                1,
                2,
                3,
                96,
                97,
                98,
                99,
                100,
                101,
                Integer.MAX_VALUE));
  }

  private final Inputs inputs;
  private final String className;
  private final Mutations mutations;
  private final ClassModel model;
  private final Class<?> original;

  /// `calls/Omitted.java`, for example, compiled, for the mutants of `operator`.
  ClaimFixture(String file, String operator) throws IOException {
    this(file, operator, Inputs.TEXTS);
  }

  ClaimFixture(String file, String operator, Inputs inputs) throws IOException {
    this.inputs = inputs;
    this.className = file.replace(".java", "").replace('/', '.');
    this.mutations =
        new Mutations(ClassFile.of(), Policy.NONE.withOperators(List.of(operator), List.of()));
    var source = Javac.Source.read(ROOT.resolve(file), className);
    var bytes = Javac.compile(List.of(source), List.of(), "-g").get(className);
    this.model = ClassFile.of().parse(Objects.requireNonNull(bytes));
    this.original = load(bytes);
  }

  /// Every mutant of the fixture's own methods, not of the claims.
  List<Mutant> mutants() {
    return mutations.find(model).stream()
        .filter(mutant -> !sourceMethod(mutant).contains("Claim"))
        .toList();
  }

  List<Mutant> running() {
    return mutants().stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
  }

  /// What the operator changes in each method: its label, and why it's skipped if it is.
  List<String> changesIn(String method) {
    return mutants().stream()
        .filter(mutant -> sourceMethod(mutant).equals(method))
        .map(
            mutant ->
                mutant.replacement() + mutant.skipped().map(why -> " (" + why + ")").orElse(""))
        .toList();
  }

  /// The mutants that don't behave as their claim's source does, or change nothing.
  List<String> claimsThatLie() throws ReflectiveOperationException {
    var failures = new ArrayList<String>();
    for (var mutant : running()) {
      if (!mutant.descriptor().startsWith(inputs.descriptor())) {
        continue; // a helper, which a test of its own calls through its caller
      }
      var method = sourceMethod(mutant);
      var mutated = outcomes(mutated(mutant), method);
      if (!mutated.equals(outcomes(original, claimOf(mutant)))) {
        failures.add(describe(mutant) + ": isn't what the claim says");
      }
      if (mutated.equals(outcomes(original, method))) {
        failures.add(describe(mutant) + ": changes nothing");
      }
    }
    return failures;
  }

  /// The skipped mutants that don't behave as the original does.
  List<String> skippedThatDiffer() throws ReflectiveOperationException {
    var failures = new ArrayList<String>();
    for (var mutant : mutants()) {
      if (mutant.skipped().filter(why -> why.startsWith("equivalent")).isPresent()) {
        var method = sourceMethod(mutant);
        if (!outcomes(mutated(mutant), method).equals(outcomes(original, method))) {
          failures.add(describe(mutant));
        }
      }
    }
    return failures;
  }

  private String claimOf(Mutant mutant) {
    var method = sourceMethod(mutant);
    var label = mutant.replacement();
    var named = method + "Claim_" + label.substring(label.lastIndexOf(' ') + 1);
    return java.util.Arrays.stream(original.getDeclaredMethods())
            .anyMatch(each -> each.getName().equals(named))
        ? named
        : method + "Claim";
  }

  /// `inALambda`, for a mutant in `lambda$inALambda$0`.
  static String sourceMethod(Mutant mutant) {
    var name = mutant.methodName();
    return name.startsWith("lambda$") ? name.substring(7, name.lastIndexOf('$')) : name;
  }

  Class<?> mutated(Mutant mutant) {
    return load(mutations.apply(model, mutant));
  }

  private List<String> outcomes(Class<?> owner, String method) throws ReflectiveOperationException {
    var outcomes = new ArrayList<String>();
    var found = owner.getDeclaredMethod(method, inputs.type(), inputs.type());
    found.setAccessible(true);
    for (Object a : inputs.values()) {
      for (Object b : inputs.values()) {
        outcomes.add(a + "," + b + ": " + outcome(found, a, b));
      }
    }
    return outcomes;
  }

  String outcomeOfOriginal(String method, String a, String b) throws ReflectiveOperationException {
    return outcome(original, method, a, b);
  }

  /// What the method gives for the inputs, or what it threw.
  static String outcome(Class<?> owner, String name, String a, String b)
      throws ReflectiveOperationException {
    var method = owner.getDeclaredMethod(name, String.class, String.class);
    method.setAccessible(true);
    return outcome(method, a, b);
  }

  private static String outcome(java.lang.reflect.Method method, Object... arguments)
      throws ReflectiveOperationException {
    try {
      var receiver =
          java.lang.reflect.Modifier.isStatic(method.getModifiers())
              ? null
              : instance(method.getDeclaringClass());
      var result = method.invoke(receiver, arguments);
      return result instanceof Object[] array
          ? java.util.Arrays.deepToString(array)
          : String.valueOf(result);
    } catch (InvocationTargetException thrown) {
      return "threw " + thrown.getCause().getClass().getSimpleName();
    }
  }

  private static Object instance(Class<?> owner) throws ReflectiveOperationException {
    var constructor = owner.getDeclaredConstructor();
    constructor.setAccessible(true);
    return constructor.newInstance();
  }

  static String describe(Mutant mutant) {
    return "%s line %d %s".formatted(mutant.methodName(), mutant.line(), mutant.replacement());
  }

  private Class<?> load(byte[] bytes) {
    try {
      return Javac.loader(Map.of(className, bytes)).loadClass(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
