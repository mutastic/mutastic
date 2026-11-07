package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// A fixture class that writes each mutant's claim out as source beside its method: `xClaim` is `x`
/// as its card says the mutant makes it, and `xClaim_name` if the method has one change for each of
/// several names. Every method takes two of the fixture's [Inputs]. Truth tests run for every
/// mutant that reaches them, so it's cheap to have one: it's compiled with [Javac] once however
/// many mutants it has, and not until a test uses it; a test's calls for its mutants find them
/// once; and what a class's methods give for the inputs comes from [Kept], whose key is the class's
/// bytes.
///
/// Hold one in an instance field, so each test has its own. One test's mutants mustn't be another
/// test's: the coverage pass runs a class's tests together, and a test that took them from an
/// earlier one would be recorded as reaching none of the code that finds them, so it would never
/// run against a mutant of that code.
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
  private final String file;
  private final String operator;
  private final String className;
  private boolean prepared;
  private @Nullable Compiled compiled;
  private @Nullable List<Mutant> found;

  /// The fixture, compiled, with the mutations of its operator.
  ///
  /// @param classes what javac gave for it, nested classes included: an `enum` a method switches
  ///   on, say
  /// @param identity what its nested classes are, in a key for what its code does
  private record Compiled(
      Map<String, byte[]> classes, Mutations mutations, ClassModel model, String identity) {}

  /// `calls/Omitted.java`, for example, compiled, for the mutants of `operator`.
  ClaimFixture(String file, String operator) {
    this(file, operator, Inputs.TEXTS);
  }

  ClaimFixture(String file, String operator, Inputs inputs) {
    this.inputs = inputs;
    this.file = file;
    this.operator = operator;
    this.className = file.replace(".java", "").replace('/', '.');
  }

  private Compiled compiled() {
    if (compiled == null) {
      try {
        var source = Javac.Source.read(ROOT.resolve(file), className);
        var classes = Javac.compile(List.of(source), List.of(), "-g");
        var mutations =
            new Mutations(
                resolvingNested(classes),
                Policy.NONE.withOperators(List.of(operator), List.of()),
                new Supertypes(name -> fixtureOrJdkClass(classes, name)));
        var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(className)));
        if (prepared) {
          mutations.prepare(List.of(model));
        }
        compiled = new Compiled(classes, mutations, model, source.text());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return compiled;
  }

  private byte[] originalBytes() {
    return Objects.requireNonNull(compiled().classes().get(className));
  }

  /// Stack maps and verifying need supertypes, and a fixture's nested classes aren't on any
  /// classpath, as a project's classes are found in a real run.
  private static ClassFile resolvingNested(Map<String, byte[]> classes) {
    var resolver =
        ClassHierarchyResolver.defaultResolver()
            .orElse(
                ClassHierarchyResolver.ofResourceParsing(
                    type -> {
                      var bytes = classes.get(type.packageName() + "." + type.displayName());
                      return bytes == null ? null : new java.io.ByteArrayInputStream(bytes);
                    }));
    return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
  }

  private static java.io.InputStream fixtureOrJdkClass(Map<String, byte[]> classes, String name) {
    var bytes = classes.get(name.replace('/', '.'));
    return bytes != null
        ? new java.io.ByteArrayInputStream(bytes)
        : ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class");
  }

  /// The fixture's class, as javac gave it.
  ClassModel model() {
    return compiled().model();
  }

  /// Lets the filters see the fixture as a module, as a real run does: what's arid for what calls
  /// it, say.
  ClaimFixture prepared() {
    prepared = true;
    return this;
  }

  /// Every mutant of the fixture's own methods, not of the claims.
  List<Mutant> mutants() {
    if (found == null) {
      found =
          compiled().mutations().find(compiled().model()).stream()
              .filter(mutant -> !sourceMethod(mutant).contains("Claim"))
              .toList();
    }
    return found;
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
      var mutated = outcomes(mutatedBytes(mutant), method);
      if (!mutated.equals(outcomes(originalBytes(), claimOf(mutant)))) {
        failures.add(describe(mutant) + ": isn't what the claim says");
      }
      if (mutated.equals(outcomes(originalBytes(), method))) {
        failures.add(describe(mutant) + ": changes nothing");
      }
    }
    return failures;
  }

  /// [#claimsThatLie], for a fixture whose claims take the value the mutant's label ends in as a
  /// third parameter: `int atClaim(int a, int b, int limit)`. So one claim is every value's.
  List<String> valueClaimsThatLie() throws ReflectiveOperationException {
    var failures = new ArrayList<String>();
    for (var mutant : running()) {
      if (!mutant.descriptor().startsWith(inputs.descriptor())) {
        continue;
      }
      var method = sourceMethod(mutant);
      var mutated = outcomes(mutatedBytes(mutant), method);
      if (!mutated.equals(valueClaim(method, mutant))) {
        failures.add(describe(mutant) + ": isn't what the claim says");
      }
      if (mutated.equals(outcomes(originalBytes(), method))) {
        failures.add(describe(mutant) + ": changes nothing");
      }
    }
    return failures;
  }

  private List<String> valueClaim(String method, Mutant mutant)
      throws ReflectiveOperationException {
    var label = mutant.replacement();
    var written = label.substring(label.lastIndexOf(' ') + 1);
    return Kept.outcomes(
        keyFor(originalBytes(), method + "Claim", written),
        () -> {
          var named = method + "Claim";
          var claim =
              java.util.Arrays.stream(load(originalBytes()).getDeclaredMethods())
                  .filter(each -> each.getName().equals(named) && each.getParameterCount() == 3)
                  .findFirst()
                  .orElseThrow(() -> new NoSuchMethodException(named));
          claim.setAccessible(true);
          var value = parsed(written, claim.getParameterTypes()[2]);
          var outcomes = new ArrayList<String>();
          for (Object a : inputs.values()) {
            for (Object b : inputs.values()) {
              outcomes.add(a + "," + b + ": " + outcome(claim, a, b, value));
            }
          }
          return outcomes;
        });
  }

  /// What a class's method gives depends only on the class's bytes, the fixture's other classes and
  /// the inputs: fixtures call nothing of mutastic's.
  private List<String> keyFor(byte[] bytes, String... rest) {
    var key = new ArrayList<>(List.of(HexFormat.of().formatHex(bytes), compiled().identity()));
    key.add(inputs.values().toString());
    key.addAll(List.of(rest));
    return key;
  }

  private static Object parsed(String value, Class<?> type) {
    if (type == String.class) {
      return value.substring(1, value.length() - 1).replace("\\\\", "\\");
    }
    if (type == long.class) {
      return Long.valueOf(value);
    }
    if (type == float.class) {
      return Float.valueOf(value);
    }
    if (type == double.class) {
      return Double.valueOf(value);
    }
    return type == char.class ? (Object) (char) Integer.parseInt(value) : Integer.valueOf(value);
  }

  /// What a mutant's method gives for two of the fixture's inputs.
  String outcomeOf(Mutant mutant, Object a, Object b) throws ReflectiveOperationException {
    var method =
        mutated(mutant).getDeclaredMethod(sourceMethod(mutant), inputs.type(), inputs.type());
    method.setAccessible(true);
    return outcome(method, a, b);
  }

  /// The skipped mutants that don't behave as the original does.
  List<String> skippedThatDiffer() throws ReflectiveOperationException {
    var failures = new ArrayList<String>();
    for (var mutant : mutants()) {
      if (mutant.skipped().filter(why -> why.startsWith("equivalent")).isPresent()) {
        var method = sourceMethod(mutant);
        if (!outcomes(mutatedBytes(mutant), method).equals(outcomes(originalBytes(), method))) {
          failures.add(describe(mutant));
        }
      }
    }
    return failures;
  }

  private String claimOf(Mutant mutant) {
    var method = sourceMethod(mutant);
    var label = mutant.replacement();
    var lastWord = label.substring(label.lastIndexOf(' ') + 1);
    var named = method + "Claim_" + lastWord.substring(lastWord.lastIndexOf('/') + 1);
    return compiled().model().methods().stream()
            .anyMatch(each -> each.methodName().equalsString(named))
        ? named
        : method + "Claim";
  }

  /// `inALambda`, for a mutant in `lambda$inALambda$0`.
  static String sourceMethod(Mutant mutant) {
    var name = mutant.methodName();
    return name.startsWith("lambda$") ? name.substring(7, name.lastIndexOf('$')) : name;
  }

  Class<?> mutated(Mutant mutant) {
    return load(mutatedBytes(mutant));
  }

  /// The mutant's class, which comes from the code under test every time.
  private byte[] mutatedBytes(Mutant mutant) {
    return compiled().mutations().apply(compiled().model(), mutant);
  }

  private List<String> outcomes(byte[] bytes, String method) throws ReflectiveOperationException {
    return Kept.outcomes(
        keyFor(bytes, method),
        () -> {
          var outcomes = new ArrayList<String>();
          var called = load(bytes).getDeclaredMethod(method, inputs.type(), inputs.type());
          called.setAccessible(true);
          for (Object a : inputs.values()) {
            for (Object b : inputs.values()) {
              outcomes.add(a + "," + b + ": " + outcome(called, a, b));
            }
          }
          return outcomes;
        });
  }

  String outcomeOfOriginal(String method, Object a, Object b) throws ReflectiveOperationException {
    var called = load(originalBytes()).getDeclaredMethod(method, inputs.type(), inputs.type());
    called.setAccessible(true);
    return outcome(called, a, b);
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
      var loaded = new java.util.HashMap<>(compiled().classes());
      loaded.put(className, bytes);
      return Javac.loader(loaded).loadClass(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
