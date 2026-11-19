package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.engine.ClaimFixture.Inputs;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/// What only names a thread, sets its priority, its stack or how long a pool's idle threads live is
/// arid; a pool's largest size beside a queue with no limit is equivalent; and what decides
/// whether, when or how often work runs is still made, and runs. Every operator runs on the
/// fixtures.
class ThreadSettingsTest {
  private static final String NAMES = " (arid: only names a thread)";
  private static final String PRIORITY = " (arid: only sets a priority hint)";
  private static final String KEEP_ALIVE = " (arid: only sets how long idle threads live)";
  private static final String STACK = " (arid: only sizes a thread's stack)";
  private static final String LARGEST =
      " (equivalent: differs only when the pool grows past its core size. That can't happen"
          + " because its queue has no limit, so it never fills.)";

  /// Guava's builder, as far as the fixture calls it: the rules know it by its name.
  private static final Javac.Source GUAVA =
      new Javac.Source(
          "com.google.common.util.concurrent.ThreadFactoryBuilder",
          """
          package com.google.common.util.concurrent;

          public final class ThreadFactoryBuilder {
            public ThreadFactoryBuilder setNameFormat(String format) { return this; }
            public ThreadFactoryBuilder setPriority(int priority) { return this; }
            public ThreadFactoryBuilder setDaemon(boolean daemon) { return this; }
            public java.util.concurrent.ThreadFactory build() { return Thread::new; }
          }
          """);

  private static final Javac.Source GUAVA_USE =
      new Javac.Source(
          "threads.Guava",
          """
          package threads;

          import com.google.common.util.concurrent.ThreadFactoryBuilder;

          class Guava {
            static java.util.concurrent.ThreadFactory factory() {
              return new ThreadFactoryBuilder()
                  .setNameFormat("guava-worker-%d")
                  .setPriority(7)
                  .setDaemon(true)
                  .build();
            }

            static void steps(ThreadFactoryBuilder builder) {
              builder.setNameFormat("step-%d");
              builder.setPriority(2);
            }
          }
          """);

  private final ClaimFixture largest =
      new ClaimFixture("threads/Largest.java", "mutastic/constant-value", Inputs.NUMBERS);

  @Test
  void whatOnlyNamesAThreadIsArid() throws Exception {
    var found = found(read("threads/Threads.java"));

    assertThat(in(found, "named"))
        .contains(
            "remove-void-call remove call to setName" + NAMES,
            "string-constant string \"background-task\" to \"\"" + NAMES);
    assertThat(in(found, "namedAtBirth"))
        .contains("string-constant string \"squarer\" to \"\"" + NAMES);
    assertThat(in(found, "worker"))
        .contains("string-constant string \"ref-worker\" to \"\"" + NAMES);
    assertThat(in(found, "formatted"))
        .contains("string-constant string \"pool-%d\" to \"\"" + NAMES);
    assertThat(in(found, "built"))
        .contains(
            "string-constant string \"plat-\" to \"\"" + NAMES,
            "constant-value constant 0 to 1" + NAMES,
            "constant-value constant 0 to -1",
            "boolean-constant constant true to false");
    assertThat(in(found, "virtual"))
        .contains(
            "string-constant string \"virt-\" to \"\"" + NAMES,
            "constant-value constant 0 to 1" + NAMES,
            "constant-value constant 0 to -1");
  }

  /// Text made of a parameter is `"null-1"` when it's `null`; a `null` name itself throws.
  @Test
  void aParameterIsAridOnlyWhereItsMadeIntoAName() throws Exception {
    var found = found(read("threads/Threads.java"));

    assertThat(in(found, "lambdaFactory"))
        .contains("null-parameter null parameter 0 prefix" + NAMES);
    assertThat(in(found, "givenName")).contains("null-parameter null parameter 1 name");
    assertThat(in(found, "prefixed")).contains("null-parameter null parameter 1 prefix");
  }

  /// In range, a priority is a hint; out of range, it throws, and that runs.
  @Test
  void aPriorityInRangeIsArid() throws Exception {
    var found = found(read("threads/Threads.java"));

    assertThat(in(found, "urgent"))
        .contains(
            "remove-void-call remove call to setPriority" + PRIORITY,
            "constant-value constant 10 to 9" + PRIORITY,
            "constant-value constant 10 to 1" + PRIORITY,
            "constant-value constant 10 to 11",
            "constant-value constant 10 to 0",
            "constant-value constant 10 to -1");
    assertThat(in(found, "lowly"))
        .contains(
            "constant-value constant 3 to 4" + PRIORITY,
            "constant-value constant 3 to 2" + PRIORITY,
            "constant-value constant 3 to 0");
  }

  @Test
  void aKeepAliveAndAStackThatStayAboveZeroAreArid() throws Exception {
    var found = found(read("threads/Threads.java"));

    assertThat(in(found, "bounded"))
        .contains(
            "constant-value constant 30 to 31" + KEEP_ALIVE,
            "constant-value constant 30 to 29" + KEEP_ALIVE,
            "constant-value constant 30 to 1" + KEEP_ALIVE,
            "constant-value constant 30 to 0",
            "constant-value constant 30 to -1");
    assertThat(in(found, "idle"))
        .contains(
            "constant-value constant 10 to 11" + KEEP_ALIVE, "constant-value constant 10 to 0");
    for (var method : List.of("deep", "builtDeep")) {
      assertThat(in(found, method))
          .as(method)
          .contains(
              "constant-value constant 1048576 to 1048577" + STACK,
              "constant-value constant 1048576 to 1" + STACK,
              "constant-value constant 1048576 to 0",
              "constant-value constant 1048576 to -1");
    }
  }

  /// Only beside a queue made with no limit, and only from the core size up: below it, or below
  /// `1`, the constructor throws.
  @Test
  void aLargestPoolSizeBesideAQueueWithNoLimitIsEquivalent() throws Exception {
    var found = found(read("threads/Threads.java"));

    assertThat(in(found, "unbounded"))
        .contains(
            "constant-value constant 4 to 5" + LARGEST,
            "constant-value constant 4 to 3" + LARGEST,
            "constant-value constant 4 to 1",
            "constant-value constant 4 to 0");
    for (var method : List.of("bounded", "handedOff")) {
      assertThat(in(found, method))
          .as(method)
          .contains("constant-value constant 4 to 5", "constant-value constant 4 to 3");
    }
    assertThat(largest.mutants())
        .extracting(mutant -> mutant.skipped().orElse(""))
        .contains(LARGEST.substring(2, LARGEST.length() - 1));
    assertThat(largest.skippedThatDiffer()).isEmpty();
  }

  /// Starting, stopping, waiting, a daemon flag and a pool's size decide whether, when or how often
  /// work runs, and whether the JVM can exit: their mutants run. A sleep stays arid.
  @Test
  void whatDecidesWhetherWorkRunsStillRuns() throws Exception {
    var found = found(read("threads/Threads.java"));

    assertThat(in(found, "fireAndForget"))
        .contains(
            "remove-void-call remove call to setDaemon",
            "remove-void-call remove call to start",
            "boolean-constant constant true to false");
    assertThat(in(found, "stop")).contains("remove-void-call remove call to interrupt");
    assertThat(in(found, "pause"))
        .contains("remove-void-call remove call to sleep (arid: only waits)");
    assertThat(in(found, "fixed"))
        .contains("constant-value constant 3 to 4", "constant-value constant 3 to 2");
    assertThat(in(found, "unbounded"))
        .contains("constant-value constant 2 to 3", "constant-value constant 2 to 1");
  }

  /// A name only logged isn't a name read.
  @Test
  void aClassThatReadsNamesOrIsAFactoryKeepsItsNames() throws Exception {
    for (var file : List.of("threads/NamingFactory.java", "threads/ReadsNames.java")) {
      assertThat(found(read(file)))
          .as(file)
          .anyMatch(each -> each.contains("setName"))
          .noneMatch(each -> each.endsWith(NAMES));
    }
    assertThat(in(found(read("threads/Threads.java")), "named"))
        .contains("remove-void-call remove call to setName" + NAMES);
  }

  @Test
  void aClassThatReadsPrioritiesOrResizesAPoolKeepsThem() throws Exception {
    assertThat(found(read("threads/ReadsPriorities.java")))
        .anyMatch(each -> each.contains("setPriority"))
        .noneMatch(each -> each.endsWith(PRIORITY));
    assertThat(found(read("threads/Resizes.java")))
        .contains("unbounded: constant-value constant 4 to 5")
        .noneMatch(each -> each.endsWith(LARGEST));
  }

  @Test
  void guavasNamesAndPrioritiesAreArid() throws Exception {
    var classes = Javac.compile(List.of(GUAVA_USE), List.of(GUAVA), "-g");
    var found = found(classes, GUAVA_USE.className());

    assertThat(in(found, "factory"))
        .contains(
            "string-constant string \"guava-worker-%d\" to \"\"" + NAMES,
            "constant-value constant 7 to 8" + PRIORITY,
            "constant-value constant 7 to -1",
            "boolean-constant constant true to false");
    assertThat(in(found, "steps"))
        .contains(
            "remove-discarded-call remove call to setNameFormat" + NAMES,
            "remove-discarded-call remove call to setPriority" + PRIORITY);
  }

  private static Javac.Source read(String file) throws IOException {
    return Javac.Source.read(
        ClaimFixture.ROOT.resolve(file), file.replace(".java", "").replace('/', '.'));
  }

  private static List<String> found(Javac.Source source) throws IOException {
    return found(Javac.compile(List.of(source), List.of(), "-g"), source.className());
  }

  /// Each mutant of the class, `method: operator label (why it's skipped)`, lambdas by the method
  /// they're in.
  private static List<String> found(java.util.Map<String, byte[]> classes, String className) {
    var mutations =
        new Mutations(
            ClaimFixture.resolvingNested(classes),
            Policy.NONE.withOperators(List.of("all"), List.of()),
            new Supertypes(name -> ClaimFixture.fixtureOrJdkClass(classes, name)));
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(className)));
    mutations.prepare(List.of(model));
    return mutations.find(model).stream().map(ThreadSettingsTest::described).toList();
  }

  private static String described(Mutant mutant) {
    var operator = mutant.operator().id();
    var original = mutant.original();
    var called =
        mutant.replacement().equals("remove call")
            ? " to " + original.substring(original.lastIndexOf('.') + 1, original.indexOf('('))
            : "";
    return "%s: %s %s%s%s"
        .formatted(
            ClaimFixture.sourceMethod(mutant),
            operator.substring(operator.indexOf('/') + 1),
            mutant.replacement(),
            called,
            mutant.skipped().map(why -> " (" + why + ")").orElse(""));
  }

  private static List<String> in(List<String> found, String method) {
    return found.stream()
        .filter(each -> each.startsWith(method + ": "))
        .map(each -> each.substring(method.length() + 2))
        .toList();
  }
}
