package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.tools.ToolProvider;

/// A fixture class that writes each mutant's claim out as source beside its method: `xClaim` is `x`
/// as its card says the mutant makes it, and `xClaim_name` if the method has one change for each of
/// several names. Every method is static and takes two strings. It's compiled once, however many
/// mutants it has, as truth tests run for every mutant that reaches them.
final class ClaimFixture {
  static final Path ROOT = Path.of("src/test/resources/truth");
  private static final List<String> TEXTS =
      List.of("a", "b", "aa", "ab", "ba", "aba", " a", "a ", " a ", "A", "aB", "1", "abcde", "<b>");

  private final String className;
  private final Mutations mutations;
  private final ClassModel model;
  private final Class<?> original;

  /// Compiles `calls/Omitted.java`, for example, under `temp`, for the mutants of `operator`.
  ClaimFixture(Path temp, String file, String operator) throws IOException {
    var name = file.replace(".java", "");
    this.className = name.replace('/', '.');
    this.mutations =
        new Mutations(ClassFile.of(), Policy.NONE.withOperators(List.of(operator), List.of()));
    var out = Files.createDirectories(temp.resolve(className));
    var errors = new ByteArrayOutputStream();
    int status =
        Objects.requireNonNull(ToolProvider.getSystemJavaCompiler())
            .run(null, null, errors, "-g", "-d", out.toString(), ROOT.resolve(file).toString());
    assertThat(status).as("javac: %s", errors).isZero();
    var bytes = Files.readAllBytes(out.resolve(name + ".class"));
    this.model = ClassFile.of().parse(bytes);
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
    for (String a : TEXTS) {
      for (String b : TEXTS) {
        outcomes.add(a + "," + b + ": " + outcome(owner, method, a, b));
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
    try {
      var result = method.invoke(null, a, b);
      return result instanceof Object[] array
          ? java.util.Arrays.deepToString(array)
          : String.valueOf(result);
    } catch (InvocationTargetException thrown) {
      return "threw " + thrown.getCause().getClass().getSimpleName();
    }
  }

  static String describe(Mutant mutant) {
    return "%s line %d %s".formatted(mutant.methodName(), mutant.line(), mutant.replacement());
  }

  private Class<?> load(byte[] bytes) {
    try {
      return new OneClass(className, bytes).loadClass(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  /// The fixture's class from the given bytes, in a loader of its own.
  private static final class OneClass extends ClassLoader {
    private final String name;
    private final byte[] bytes;

    OneClass(String name, byte[] bytes) {
      super(ClaimFixture.class.getClassLoader());
      this.name = name;
      this.bytes = bytes.clone();
    }

    @Override
    protected Class<?> loadClass(String wanted, boolean resolve) throws ClassNotFoundException {
      return wanted.equals(name) || wanted.startsWith(name + "$")
          ? defineClass(wanted, bytes, 0, bytes.length)
          : super.loadClass(wanted, resolve);
    }
  }
}
