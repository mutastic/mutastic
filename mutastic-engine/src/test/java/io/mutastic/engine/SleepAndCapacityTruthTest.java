package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// A number whose only use is how long a sleep waits, or how much room a collection starts with, is
/// arid however javac writes it: straight in, worked out on the way, or turned into a time first. A
/// number that also goes elsewhere, into a local or a timeout, is counted, and so is a constant
/// made a value the call throws for.
class SleepAndCapacityTruthTest {
  private static final String WAITS = "arid: only sets how long to wait";
  private static final String SIZES = "arid: only sizes a collection's first allocation";
  private static final String GROWS = "arid: only sets when a hash table grows";

  private static final String SLEEPS =
      """
      package shapes;

      import java.util.concurrent.Future;
      import java.util.concurrent.TimeUnit;

      class Sleeps {
        static void scaled(int seconds) throws InterruptedException {
          Thread.sleep(seconds * 1000L);
        }

        static void inUnits(int seconds) throws InterruptedException {
          TimeUnit.SECONDS.sleep(seconds * 2);
        }

        static void converted(int seconds) throws InterruptedException {
          Thread.sleep(TimeUnit.SECONDS.toMillis(seconds + 1));
        }

        static void backoff(int attempt) throws InterruptedException {
          long delay = 100L * attempt;
          Thread.sleep(delay);
        }

        static boolean timeout(Future<?> result, int seconds) throws Exception {
          result.get(seconds * 2, TimeUnit.SECONDS);
          return true;
        }

        static void joined(Thread worker, int seconds) throws InterruptedException {
          worker.join(seconds * 1000L);
        }
      }
      """;

  private static final String SIZED =
      """
      package shapes;

      import java.util.ArrayList;
      import java.util.HashMap;
      import java.util.List;
      import java.util.Map;

      class Sized {
        static List<String> scaled(int n) {
          return new ArrayList<>(n * 2);
        }

        static Map<String, String> loaded(int n) {
          return new HashMap<>(n + 1, 0.75f);
        }

        static StringBuilder fixed() {
          return new StringBuilder(64);
        }

        static int shared(int n) {
          int capacity = n * 2;
          List<String> names = new ArrayList<>(capacity);
          names.add("a");
          return capacity + names.size();
        }

        static int[] anArray(int n) {
          return new int[n * 2];
        }
      }
      """;

  /// What only the JDK since 19 has, and Guava's sized factories, here a stand-in for Guava's.
  private static final String MODERN =
      """
      package shapes;

      import com.google.common.collect.Lists;
      import java.time.Duration;
      import java.util.HashMap;
      import java.util.List;
      import java.util.Map;

      class Modern {
        static void slept(int seconds) throws InterruptedException {
          Thread.sleep(Duration.ofSeconds(seconds * 2));
        }

        static Map<String, String> sized(int n) {
          return HashMap.newHashMap(n * 2);
        }

        static List<String> guava(int n) {
          return Lists.newArrayListWithExpectedSize(n + 4);
        }
      }
      """;

  private static final String GUAVA =
      """
      package com.google.common.collect;

      import java.util.ArrayList;
      import java.util.List;

      public final class Lists {
        public static <E> List<E> newArrayListWithExpectedSize(int size) {
          return new ArrayList<>(size);
        }
      }
      """;

  @ParameterizedTest
  @CsvSource({"8, -g", "8, -g:none", "11, -g", "17, -g:none", "21, -g", "25, -g", "25, -g:none"})
  void aSleepsTimeIsAridInEveryShapeJavacWrites(String release, String debug) throws Exception {
    var found = found("shapes.Sleeps", SLEEPS, List.of(), release, debug);

    assertThat(found.get("scaled"))
        .containsExactlyInAnyOrder(
            "LDIV " + WAITS,
            "LREM " + WAITS,
            "constant 1000 to 1001 " + WAITS,
            "constant 1000 to 999 " + WAITS,
            "constant 1000 to 0 " + WAITS,
            "constant 1000 to 1 " + WAITS,
            // Thread.sleep throws for a negative time.
            "constant 1000 to -1 counted",
            "LADD " + WAITS,
            "LSUB " + WAITS,
            "keep first " + WAITS);
    // TimeUnit.sleep doesn't sleep for a negative time: still only how long.
    assertThat(found.get("inUnits")).isNotEmpty().allMatch(change -> change.endsWith(WAITS));
    assertThat(found.get("converted"))
        .contains("constant 1 to -1 counted")
        .filteredOn(change -> !change.equals("constant 1 to -1 counted"))
        .isNotEmpty()
        .allMatch(change -> change.endsWith(WAITS));
    // Stored, the time is the code's to use again: a backoff's state stays counted.
    assertThat(found.get("backoff")).isNotEmpty().allMatch(change -> change.endsWith("counted"));
    // A timeout decides whether the code gives up, and a timed join whether it waits for the work.
    assertThat(found.get("timeout")).isNotEmpty().allMatch(change -> change.endsWith("counted"));
    assertThat(found.get("joined")).isNotEmpty().allMatch(change -> change.endsWith("counted"));
  }

  @ParameterizedTest
  @CsvSource({"8, -g", "8, -g:none", "11, -g", "17, -g:none", "21, -g", "25, -g", "25, -g:none"})
  void aCapacityIsAridInEveryShapeJavacWrites(String release, String debug) throws Exception {
    var found = found("shapes.Sized", SIZED, List.of(), release, debug);

    assertThat(found.get("scaled"))
        .contains("constant 2 to 3 " + SIZES, "constant 2 to 0 " + SIZES)
        .contains("constant 2 to -1 counted")
        .filteredOn(change -> !change.equals("constant 2 to -1 counted"))
        .allMatch(change -> change.endsWith(SIZES));
    assertThat(found.get("loaded"))
        .contains(
            "ISUB " + SIZES,
            "constant 0.75 to 1.75 " + GROWS,
            "constant 0.75 to 1.0 " + GROWS,
            // A load factor of zero or below throws.
            "constant 0.75 to -0.25 counted",
            "constant 0.75 to 0.0 counted",
            "constant 0.75 to -1.0 counted");
    assertThat(found.get("fixed"))
        .containsExactlyInAnyOrder(
            "constant 64 to 65 " + SIZES,
            "constant 64 to 63 " + SIZES,
            "constant 64 to 0 " + SIZES,
            "constant 64 to 1 " + SIZES,
            "constant 64 to -1 counted");
    assertThat(found.get("shared")).isNotEmpty().noneMatch(change -> change.contains("arid"));
    assertThat(found.get("anArray")).isNotEmpty().allMatch(change -> change.endsWith("counted"));
  }

  @ParameterizedTest
  @CsvSource({"21, -g", "25, -g", "25, -g:none"})
  void whatTheNewerJdkAndGuavaHaveIsAridToo(String release, String debug) throws Exception {
    var found =
        found(
            "shapes.Modern",
            MODERN,
            List.of(new Javac.Source("com.google.common.collect.Lists", GUAVA)),
            release,
            debug);

    // Thread.sleep(Duration) doesn't sleep for a negative one.
    assertThat(found.get("slept")).isNotEmpty().allMatch(change -> change.endsWith(WAITS));
    assertThat(found.get("sized"))
        .contains("constant 2 to -1 counted")
        .filteredOn(change -> !change.equals("constant 2 to -1 counted"))
        .isNotEmpty()
        .allMatch(change -> change.endsWith(SIZES));
    assertThat(found.get("guava"))
        .contains("constant 4 to -1 counted")
        .filteredOn(change -> !change.equals("constant 4 to -1 counted"))
        .isNotEmpty()
        .allMatch(change -> change.endsWith(SIZES));
  }

  /// A sleep's time handed on in a `Duration` to something else, a timeout, is counted.
  @Test
  void aTimeThatGoesElsewhereIsCounted() throws Exception {
    var source =
        """
        package shapes;

        import java.time.Duration;
        import java.util.concurrent.CompletableFuture;
        import java.util.concurrent.TimeUnit;

        class Elsewhere {
          static Object waited(CompletableFuture<Object> result, int seconds) throws Exception {
            return result.get(Duration.ofSeconds(seconds * 2).toMillis(), TimeUnit.MILLISECONDS);
          }
        }
        """;
    var found = found("shapes.Elsewhere", source, List.of(), "25", "-g");

    assertThat(found.get("waited")).isNotEmpty().allMatch(change -> change.endsWith("counted"));
  }

  /// Each calculation's mutant in each method, `constant 2 to 3 arid: …` or `… counted`.
  private static Map<String, List<String>> found(
      String className, String text, List<Javac.Source> alongside, String release, String debug)
      throws IOException {
    var classes =
        Javac.compile(
            List.of(new Javac.Source(className, text)), alongside, "--release", release, debug);
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(className)));
    var mutations =
        new Mutations(
            ClassFile.of(),
            Policy.NONE.withOperators(
                List.of(
                    "mutastic/math",
                    "mutastic/arithmetic-replacement",
                    "mutastic/other-arithmetic",
                    "mutastic/keep-operand",
                    "mutastic/constant-value"),
                List.of()));
    var found = new java.util.TreeMap<String, List<String>>();
    for (Mutant mutant : mutations.find(model)) {
      found
          .computeIfAbsent(mutant.methodName(), name -> new ArrayList<>())
          .add(mutant.replacement() + " " + mutant.skipped().orElse("counted"));
    }
    return found;
  }
}
