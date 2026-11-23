package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.classfile.ClassFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// `mutastic:output`, in every shape javac writes: a call to a logger, its fluent builder included,
/// or to the console is arid, and so is a log-level check whose body only writes output; a print to
/// a stream the code is given stays counted, as does a check whose body does more. The fixture's
/// methods say which by their names, and the claims are checked by running the mutants: a counted
/// print changes what the stream a test hands in reads, an arid one changes nothing but the
/// console.
class OutputTruthTest {
  private static final Path FIXTURE = ClaimFixture.ROOT.resolve("output");
  private static final String CLASS = "output.Printed";

  /// The operators that take a call away.
  private static final Set<BuiltIn> REMOVALS =
      Set.of(BuiltIn.REMOVE_VOID_CALL, BuiltIn.REMOVE_DISCARDED_CALL);

  @ParameterizedTest
  @CsvSource({
    "8, -g",
    "8, -g:none",
    "11, -g",
    "11, -g:none",
    "17, -g",
    "17, -g:none",
    "21, -g",
    "21, -g:none",
    "25, -g",
    "25, -g:none"
  })
  void consoleAndLoggerCallsAreAridAndAStreamGivenIsCounted(String release, String debug)
      throws Exception {
    var found = found(compiled(release, debug), Policy.NONE);

    var only = removals(found, "only");
    assertThat(only.stream().map(Mutant::methodName).distinct()).hasSize(14);
    assertThat(only)
        .filteredOn(mutant -> !mutant.skipped().equals(Optional.of(OutputCalls.REASON)))
        .extracting(OutputTruthTest::shown)
        .isEmpty();

    var kept =
        removals(found, "kept").stream()
            .filter(mutant -> !mutant.methodName().startsWith("keptGuard"))
            .toList();
    assertThat(kept.stream().map(Mutant::methodName).distinct()).hasSize(6);
    assertThat(kept)
        .filteredOn(mutant -> mutant.original().contains("print"))
        .filteredOn(mutant -> mutant.skipped().isPresent())
        .extracting(OutputTruthTest::shown)
        .isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "8, -g",
    "8, -g:none",
    "11, -g",
    "11, -g:none",
    "17, -g",
    "17, -g:none",
    "21, -g",
    "21, -g:none",
    "25, -g",
    "25, -g:none"
  })
  void aLogLevelCheckIsAridOnlyWhenItsBodyOnlyWritesOutput(String release, String debug)
      throws Exception {
    var found = found(compiled(release, debug), Policy.NONE);

    var guards = conditions(found, "guard");
    assertThat(guards.stream().map(Mutant::methodName).distinct()).hasSize(6);
    assertThat(guards)
        .filteredOn(mutant -> !mutant.skipped().equals(Optional.of(OutputCalls.GUARD)))
        .extracting(OutputTruthTest::shown)
        .isEmpty();

    var kept = conditions(found, "keptGuard");
    assertThat(kept.stream().map(Mutant::methodName).distinct()).hasSize(7);
    assertThat(found)
        .filteredOn(mutant -> mutant.methodName().startsWith("keptGuard"))
        .filteredOn(mutant -> mutant.skipped().equals(Optional.of(OutputCalls.GUARD)))
        .extracting(OutputTruthTest::shown)
        .isEmpty();
    assertThat(
            kept.stream()
                .filter(mutant -> mutant.skipped().isEmpty())
                .map(Mutant::methodName)
                .distinct())
        .as("a counted check in each")
        .hasSize(7);
  }

  /// A print to a stream the code is given, as a field, a parameter or a local of one, is caught by
  /// a test that reads the stream: the mutant prints less, or nothing.
  @ParameterizedTest
  @CsvSource({"8, -g:none", "11, -g", "17, -g:none", "21, -g", "25, -g"})
  void aCountedPrintChangesWhatTheStreamGivenReads(String release, String debug) throws Exception {
    var classes = compiled(release, debug);
    var mutants =
        removals(found(classes, Policy.NONE), "kept").stream()
            .filter(mutant -> !mutant.methodName().startsWith("keptGuard"))
            .filter(mutant -> mutant.original().contains("print"))
            .toList();
    assertThat(mutants).hasSizeGreaterThanOrEqualTo(6);
    for (var mutant : mutants) {
      assertThat(printed(classes, mutant.methodName()))
          .as(shown(mutant))
          .isNotEqualTo(printed(mutated(classes, mutant), mutant.methodName()));
    }
  }

  /// A print to the console taken away, and a check of a level around a log line changed, changes
  /// nothing the code gives back: only what the console shows, or whether a line is logged.
  @ParameterizedTest
  @CsvSource({"8, -g:none", "11, -g", "17, -g:none", "21, -g", "25, -g"})
  void anAridOneChangesOnlyTheConsoleOrTheLog(String release, String debug) throws Exception {
    var classes = compiled(release, debug);
    var console = Set.of("onlySystemOut", "onlySystemErr", "onlyPrintf", "onlyChosenText");
    var mutants =
        found(classes, Policy.NONE).stream()
            .filter(
                mutant ->
                    console.contains(mutant.methodName())
                        || mutant.methodName().equals("onlyAConsoleLocal")
                        || mutant.methodName().equals("guardJul"))
            .filter(mutant -> mutant.skipped().filter(why -> why.contains("output")).isPresent())
            .filter(
                mutant ->
                    REMOVALS.contains(mutant.operator())
                        || mutant.operator() == BuiltIn.REMOVE_CONDITIONAL)
            .toList();
    assertThat(mutants.stream().map(Mutant::methodName).distinct()).hasSize(6);
    for (var mutant : mutants) {
      assertThat(given(mutated(classes, mutant), mutant.methodName()))
          .as(shown(mutant))
          .isEqualTo(given(classes, mutant.methodName()));
    }
  }

  /// `arid: [mutastic:output]` and `@DoMutate` bring them back; another rule's list doesn't.
  @Test
  void theOutputRuleBringsThemBack() throws Exception {
    var classes = compiled("25", "-g");
    var plain = found(classes, Policy.NONE);
    var output =
        plain.stream()
            .filter(mutant -> mutant.skipped().filter(why -> why.contains("output")).isPresent())
            .collect(Collectors.toMap(Mutant::id, mutant -> mutant.skipped().orElseThrow()));
    assertThat(output.values()).contains(OutputCalls.REASON, OutputCalls.GUARD);

    var kept = new Rule(true, CLASS, "the log is our API", List.of("mutastic:output"));
    var waits = new Rule(true, CLASS, "waits", List.of("mutastic:waits"));
    assertThat(skipsOf(found(classes, policy(kept)), output.keySet()))
        .containsOnly(Optional.empty());
    assertThat(skipsOf(found(classes, policy(waits)), output.keySet()))
        .containsExactlyInAnyOrderElementsOf(output.values().stream().map(Optional::of).toList());

    var source = source();
    var annotated =
        new Javac.Source(
            CLASS,
            source.text().replaceFirst("(?m)^(class Printed\\b)", "@DoMutate\n$1")
                + "\n@interface DoMutate { String[] arid() default {}; }\n");
    var compiled = compile(annotated, "25", "-g");
    assertThat(skipsOf(found(compiled, Policy.NONE), output.keySet()))
        .containsOnly(Optional.empty());
  }

  /// `- output: output.AuditLog#*` makes the project's own calls output, said with the method and
  /// the rule's reason, and what only goes into one with them; without the rule they count. A
  /// `mutate:` rule brings them back.
  @ParameterizedTest
  @CsvSource({"8, -g:none", "11, -g", "17, -g:none", "21, -g", "25, -g"})
  void aProjectsOwnOutputIsAridWithItsReason(String release, String debug) throws Exception {
    var classes = compiled(release, debug);
    var own = Rule.output("output.AuditLog#*", "the audit trail");

    assertThat(found(classes, Policy.NONE))
        .filteredOn(mutant -> mutant.methodName().startsWith("own"))
        .filteredOn(mutant -> mutant.skipped().isPresent())
        .extracting(OutputTruthTest::shown)
        .isEmpty();

    var found =
        found(classes, policy(own)).stream()
            .filter(mutant -> mutant.methodName().startsWith("own"))
            .toList();
    assertThat(found)
        .filteredOn(mutant -> REMOVALS.contains(mutant.operator()))
        .extracting(mutant -> mutant.original().replaceAll(" .*", "") + " " + mutant.skipped())
        .containsExactlyInAnyOrder(
            "INVOKEVIRTUAL Optional[arid: only calls AuditLog#info (the audit trail)]",
            "INVOKEVIRTUAL Optional[arid: only calls AuditLog#count (the audit trail)]",
            "INVOKEVIRTUAL Optional[arid: only calls AuditLog#info (the audit trail)]");
    assertThat(found)
        .filteredOn(mutant -> mutant.operator() == BuiltIn.STRING_CONSTANT)
        .extracting(mutant -> mutant.skipped().orElse("counted"))
        .contains("arid: only feeds AuditLog#info (the audit trail)");
    assertThat(conditions(found, "ownGuard"))
        .extracting(mutant -> mutant.skipped().orElse("counted"))
        .isNotEmpty()
        .containsOnly(OutputCalls.GUARD);

    var kept = new Rule(true, CLASS, "the audit is checked", List.of("mutastic:output"));
    assertThat(found(classes, new Policy(List.of(own, kept), List.of())))
        .filteredOn(mutant -> mutant.methodName().startsWith("own"))
        .filteredOn(mutant -> mutant.skipped().isPresent())
        .extracting(OutputTruthTest::shown)
        .isEmpty();
  }

  private static List<Optional<String>> skipsOf(List<Mutant> found, Set<String> ids) {
    return found.stream().filter(mutant -> ids.contains(mutant.id())).map(Mutant::skipped).toList();
  }

  private static Policy policy(Rule rule) {
    return new Policy(List.of(rule), List.of());
  }

  private static List<Mutant> removals(List<Mutant> found, String prefix) {
    return found.stream()
        .filter(mutant -> mutant.methodName().startsWith(prefix))
        .filter(mutant -> REMOVALS.contains(mutant.operator()))
        .toList();
  }

  private static List<Mutant> conditions(List<Mutant> found, String prefix) {
    return found.stream()
        .filter(mutant -> mutant.methodName().startsWith(prefix))
        .filter(mutant -> mutant.operator() == BuiltIn.REMOVE_CONDITIONAL)
        .filter(mutant -> mutant.original().matches("IF(EQ|NE)\\b.*"))
        .toList();
  }

  private static String shown(Mutant mutant) {
    return "%s %s %s %s %s"
        .formatted(
            mutant.methodName(),
            mutant.operator(),
            mutant.original(),
            mutant.replacement(),
            mutant.skipped().orElse("counted"));
  }

  /// What the method prints to the stream it's given, as a field or a parameter, for `a` of 3.
  private static String printed(Map<String, byte[]> classes, String method) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var text = new StringWriter();
    try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var writer = new PrintWriter(text, true)) {
      var type = Javac.loader(classes).loadClass(CLASS);
      var instance = instance(type, stream);
      var called = method(type, method);
      var types = called.getParameterTypes();
      var arguments = new ArrayList<Object>();
      for (var each : types) {
        arguments.add(
            each == PrintStream.class ? stream : each == PrintWriter.class ? writer : (Object) 3);
      }
      called.invoke(instance, arguments.toArray());
    }
    return bytes.toString(StandardCharsets.UTF_8) + text;
  }

  /// What the method gives back for each of a few values, with the console caught.
  private static List<Object> given(Map<String, byte[]> classes, String method) throws Exception {
    var type = Javac.loader(classes).loadClass(CLASS);
    var called = method(type, method);
    var results = new ArrayList<Object>();
    var out = System.out;
    var err = System.err;
    try (var quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
      System.setOut(quiet);
      System.setErr(quiet);
      for (int a : new int[] {-1, 0, 3}) {
        var arguments = new ArrayList<Object>();
        for (var each : called.getParameterTypes()) {
          arguments.add(
              each == java.util.logging.Logger.class
                  ? java.util.logging.Logger.getLogger("output.truth")
                  : (Object) a);
        }
        results.add(called.invoke(instance(type, quiet), arguments.toArray()));
      }
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
    return results;
  }

  private static Object instance(Class<?> type, PrintStream stream)
      throws ReflectiveOperationException {
    var constructor = type.getDeclaredConstructor(PrintStream.class);
    constructor.setAccessible(true);
    return constructor.newInstance(stream);
  }

  private static Method method(Class<?> type, String name) {
    var method =
        Stream.of(type.getDeclaredMethods())
            .filter(each -> each.getName().equals(name))
            .findFirst()
            .orElseThrow();
    method.setAccessible(true);
    return method;
  }

  private static Map<String, byte[]> mutated(Map<String, byte[]> classes, Mutant mutant)
      throws IOException {
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(CLASS)));
    var changed = new HashMap<>(classes);
    changed.put(CLASS, new Mutations(ClassFile.of(), all(Policy.NONE)).apply(model, mutant));
    return changed;
  }

  private static Javac.Source source() throws IOException {
    return Javac.Source.read(FIXTURE.resolve("Printed.java"), CLASS);
  }

  /// The logger APIs the fixture calls, as just enough of each to compile against.
  private static List<Javac.Source> stubs() throws IOException {
    try (var files = Files.walk(FIXTURE)) {
      var found = new ArrayList<Javac.Source>();
      for (var file : files.filter(each -> each.toString().endsWith(".java")).sorted().toList()) {
        var parts = new ArrayList<String>();
        FIXTURE.relativize(file).forEach(part -> parts.add(part.toString()));
        var name = String.join(".", parts).replaceAll("\\.java$", "");
        if (!name.equals("Printed")) {
          found.add(Javac.Source.read(file, name));
        }
      }
      return found;
    }
  }

  private static Map<String, byte[]> compiled(String release, String debug) throws IOException {
    return compile(source(), release, debug);
  }

  /// The fixture and the stubs, all loadable.
  private static Map<String, byte[]> compile(Javac.Source fixture, String release, String debug)
      throws IOException {
    var sources = new ArrayList<>(List.of(fixture));
    sources.addAll(stubs());
    return Javac.compile(sources, List.of(), "--release", release, debug);
  }

  private static Policy all(Policy policy) {
    return policy.withOperators(List.of("all"), List.of());
  }

  /// Every mutant of the fixture, every operator's.
  private static List<Mutant> found(Map<String, byte[]> classes, Policy policy) {
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(CLASS)));
    return new Mutations(ClassFile.of(), all(policy)).find(model);
  }
}
