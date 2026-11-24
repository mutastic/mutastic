package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PropertyTestsTest {
  private static final Path GRADLE = Path.of("/home/me/.gradle/caches/modules-2/files-2.1");

  private static final Path QUICKCHECK =
      GRADLE.resolve("com.pholser/junit-quickcheck-core/1.0/abc/junit-quickcheck-core-1.0.jar");
  private static final Path QUICKTHEORIES =
      GRADLE.resolve("org.quicktheories/quicktheories/0.26/abc/quicktheories-0.26.jar");
  private static final Path KOTEST =
      GRADLE.resolve("io.kotest/kotest-property-jvm/6.2.7/abc/kotest-property-jvm-6.2.7.jar");
  private static final Path JUNIT = GRADLE.resolve("junit/junit/4.13.2/abc/junit-4.13.2.jar");

  /// A jar named as jqwik's is, and nothing else of it: jqwik is only ever detected.
  private static final Path JQWIK_STUB = Path.of("/project/libs/jqwik-0.0.0-stub.jar");

  @Test
  void aTestClasspathWithoutAFrameworkHasNoPropertyTests() {
    assertThat(PropertyTests.of(List.of(JUNIT), List.of())).isEmpty();
  }

  /// Each framework is named in one order, whatever the classpath's; the seeded ones draw from 0.
  @Test
  void theFrameworksAreNamedAndTheSeedIsZero() {
    var found = PropertyTests.of(List.of(KOTEST, JUNIT, QUICKCHECK, QUICKTHEORIES), List.of());

    assertThat(found)
        .contains(
            new PropertyTests(
                List.of("junit-quickcheck", "quicktheories", "kotest"),
                Optional.of("0"),
                List.of("quicktheories", "kotest"),
                List.of("junit-quickcheck")));
    assertThat(found.orElseThrow().jvmArgs())
        .containsExactly(
            "-DQT_SEED=0",
            "-Dkotest.proptest.default.seed=0",
            "-Dkotest.proptest.seed.write-failed=false");
  }

  /// jqwik is detected by its jar's name or its group's directory, and listed as unseeded: nothing
  /// is set for it.
  @Test
  void jqwikIsDetectedOnlyAndUnseeded() {
    var cached = GRADLE.resolve("net.jqwik/jqwik-api/1.10.2/abc/api.jar");
    var maven = Path.of("/home/me/.m2/repository/net/jqwik/jqwik-engine/1.10.2/engine.jar");

    for (var jar : List.of(JQWIK_STUB, cached, maven)) {
      var found = PropertyTests.of(List.of(jar), List.of()).orElseThrow();
      assertThat(found.frameworks()).as(jar.toString()).containsExactly("jqwik");
      assertThat(found.unseeded()).containsExactly("jqwik");
      assertThat(found.seed()).isEmpty();
      assertThat(found.seeded()).isEmpty();
      assertThat(found.jvmArgs()).isEmpty();
    }
  }

  /// The person's seed is the run's, for every framework: the first they set as a whole number.
  @Test
  void thePersonsSeedIsTheRunsOne() {
    var both = List.of(QUICKTHEORIES, KOTEST);

    var kotests = PropertyTests.of(both, List.of("-Xmx1g", "-Dkotest.proptest.default.seed=42"));
    var last = PropertyTests.of(both, List.of("-DQT_SEED=7", "-DQT_SEED=8"));
    var notAWholeNumber = PropertyTests.of(both, List.of("-DQT_SEED=-3"));

    assertThat(kotests.orElseThrow().seed()).contains("42");
    assertThat(kotests.orElseThrow().jvmArgs())
        .startsWith("-DQT_SEED=42", "-Dkotest.proptest.default.seed=42");
    assertThat(last.orElseThrow().seed()).contains("8");
    assertThat(notAWholeNumber.orElseThrow().seed()).contains("0");
  }

  /// Kotest told to fail any property given a seed isn't given one.
  @Test
  void kotestFailingOnASeedIsLeftUnseeded() {
    var found =
        PropertyTests.of(List.of(KOTEST), List.of("-Dkotest.proptest.seed.fail-if-set=true"))
            .orElseThrow();

    assertThat(found.seed()).isEmpty();
    assertThat(found.unseeded()).containsExactly("kotest");
    assertThat(found.jvmArgs()).containsExactly("-Dkotest.proptest.seed.write-failed=false");
  }

  @Test
  void theNoteNamesTheFrameworksItCantSeed() {
    var seededOnly = PropertyTests.of(List.of(QUICKTHEORIES), List.of()).orElseThrow();
    var two = PropertyTests.of(List.of(QUICKCHECK, JQWIK_STUB), List.of()).orElseThrow();

    assertThat(seededOnly.note()).isEmpty();
    assertThat(two.note())
        .contains(
            "note: property tests in junit-quickcheck and jqwik run with their own seeds, which"
                + " mutastic can't set: a mutant only some of their inputs catch can be caught in"
                + " one run and missed in the next");
    assertThat(PropertyTests.listed(List.of("a"))).isEqualTo("a");
    assertThat(PropertyTests.listed(List.of("a", "b", "c"))).isEqualTo("a, b and c");
  }
}
