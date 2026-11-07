package io.mutastic.engine;

import io.mutastic.api.FlakyTest;
import io.mutastic.api.Project;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/// What a flaky test reaches that gives a different answer from one run to the next: in its own
/// class (a set-up's `ThreadLocalRandom`), and apart from that in the production methods it ran (a
/// `new Random()`), since which of the two it is changes what to do about it.
///
/// A seeded `new Random(42)` answers the same every run, so the constructors are told apart by
/// their arguments; `ThreadLocalRandom`, `SecureRandom`, `Math.random`, `UUID.randomUUID`, the
/// clocks and the time `now()`s never do.
final class FlakyEvidence {
  private static final Set<String> TIMES =
      Set.of(
          "java/time/Instant",
          "java/time/LocalDate",
          "java/time/LocalDateTime",
          "java/time/LocalTime",
          "java/time/ZonedDateTime",
          "java/time/OffsetDateTime",
          "java/time/OffsetTime",
          "java/time/Year",
          "java/time/YearMonth");

  private final Coverage coverage;
  private final Map<String, ClassModel> models;
  private final Project project;

  /// What the coverage pass recorded, the production classes it ran, and the project's tests.
  FlakyEvidence(Coverage coverage, Map<String, ClassModel> models, Project project) {
    this.coverage = coverage;
    this.models = models;
    this.project = project;
  }

  /// For each of `flaky`, what it failed with (from `failures`) and what it reaches.
  List<FlakyTest> about(List<String> flaky, Map<String, String> failures) {
    return flaky.stream().map(test -> of(test, Optional.ofNullable(failures.get(test)))).toList();
  }

  /// For each test found to fail on its own with no mutant in, by what it failed with, what it
  /// reaches.
  List<FlakyTest> aboutFailingAlone(java.util.Map<String, String> failures) {
    return failures.entrySet().stream()
        .map(failed -> of(failed.getKey(), Optional.of(failed.getValue())).failingAlone())
        .toList();
  }

  private FlakyTest of(String test, Optional<String> failure) {
    var inTest = new TreeSet<String>();
    testClass(test).ifPresent(model -> model.methods().forEach(method -> scan(method, inTest)));
    var reaches = new TreeSet<String>();
    for (var reached : coverage.methodsRunBy(test)) {
      int hash = reached.indexOf('#');
      var model = models.get(reached.substring(0, hash).replace('/', '.'));
      if (model != null) {
        var method = reached.substring(hash + 1);
        model.methods().stream()
            .filter(candidate -> signature(candidate).equals(method))
            .forEach(candidate -> scan(candidate, reaches));
      }
    }
    var chain = failure.map(causes -> List.of(causes.split(">"))).orElse(List.<String>of());
    return new FlakyTest(test, chain, List.copyOf(reaches), List.copyOf(inTest));
  }

  private static String signature(MethodModel method) {
    return method.methodName().stringValue() + method.methodType().stringValue();
  }

  /// The test's class, from the test class directories.
  private Optional<ClassModel> testClass(String test) {
    int hash = test.indexOf('#');
    var name = (hash < 0 ? test : test.substring(0, hash)).replace('.', '/') + ".class";
    return project.testClasses().stream()
        .map(root -> root.resolve(name))
        .filter(Files::isRegularFile)
        .findFirst()
        .map(
            file -> {
              try {
                return ClassFile.of().parse(Files.readAllBytes(file));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  private static void scan(MethodModel method, Set<String> reaches) {
    method.code().stream()
        .flatMap(code -> code.elementStream())
        .flatMap(
            element -> element instanceof InvokeInstruction call ? Stream.of(call) : Stream.empty())
        .forEach(call -> changing(call).ifPresent(reaches::add));
  }

  /// `new Random()`, `Instant.now`, `System.nanoTime`: what the call is, if it gives a different
  /// answer each run.
  static Optional<String> changing(InvokeInstruction call) {
    var owner = call.owner().asInternalName();
    var name = call.name().stringValue();
    var type = call.typeSymbol().descriptorString();
    var simple = owner.substring(owner.lastIndexOf('/') + 1);
    return random(owner, simple, name, type).or(() -> clock(owner, simple, name, type));
  }

  /// A source of randomness with no seed.
  private static Optional<String> random(String owner, String simple, String name, String type) {
    boolean changes =
        switch (owner) {
          case "java/util/Random", "java/util/SplittableRandom" ->
              name.equals("<init>") && type.equals("()V");
          case "java/security/SecureRandom", "java/util/concurrent/ThreadLocalRandom" ->
              name.equals("<init>") || name.equals("current") || name.equals("getInstanceStrong");
          case "java/lang/Math", "java/lang/StrictMath" -> name.equals("random");
          case "java/util/UUID" -> name.equals("randomUUID");
          case "java/util/Collections" ->
              name.equals("shuffle") && type.equals("(Ljava/util/List;)V");
          default -> false;
        };
    if (!changes) {
      return Optional.empty();
    }
    return Optional.of(name.equals("<init>") ? "new " + simple + "()" : simple + "." + name);
  }

  /// The time now, from the system's clock.
  private static Optional<String> clock(String owner, String simple, String name, String type) {
    boolean system =
        owner.equals("java/lang/System")
            && (name.equals("currentTimeMillis") || name.equals("nanoTime"));
    boolean systemClock = owner.equals("java/time/Clock") && name.startsWith("system");
    boolean now =
        TIMES.contains(owner) && name.equals("now") && !type.contains("Ljava/time/Clock;");
    boolean changes = system || systemClock || now;
    return changes ? Optional.of(simple + "." + name) : Optional.empty();
  }
}
