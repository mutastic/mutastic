package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @TempDir Path dir;

  /// proptastic is found by its entry point's class, in a jar whatever it's called, shaded or
  /// renamed, or in a directory of classes; a jar only named as its is isn't it.
  @Test
  void proptasticIsFoundByItsClassNotItsName() throws IOException {
    var shaded = jar("testkit-all.jar", "io/proptastic/Proptastic.class");
    var classes = Files.createDirectories(dir.resolve("classes/io/proptastic"));
    Files.write(classes.resolve("Proptastic.class"), new byte[] {0});
    var named = jar("proptastic-1.0.0.jar", "io/proptastic/Other.class");

    for (var entry : List.of(shaded, dir.resolve("classes"))) {
      assertThat(PropertyTests.of(List.of(JUNIT, entry), List.of()).orElseThrow().frameworks())
          .as(entry.toString())
          .containsExactly("proptastic");
    }
    assertThat(PropertyTests.of(List.of(named, dir.resolve("missing.jar")), List.of())).isEmpty();
  }

  /// proptastic reads only `0x` and hex digits, so the run's seed is given it so; it's given no
  /// saved failures, events, corpus or colour, and the seed is said decimal.
  @Test
  void proptasticIsSeededInHexAndWritesNothing() throws IOException {
    var proptastic = jar("p.jar", "io/proptastic/Proptastic.class");

    var found = PropertyTests.of(List.of(proptastic, QUICKTHEORIES), List.of("-DQT_SEED=255"));

    assertThat(found.orElseThrow().seed()).contains("255");
    assertThat(found.orElseThrow().seeded()).containsExactly("quicktheories", "proptastic");
    assertThat(found.orElseThrow().jvmArgs())
        .containsExactly(
            "-DQT_SEED=255",
            "-Dproptastic.seed=0xff",
            "-Dproptastic.replay=false",
            "-Dproptastic.events=false",
            "-Dproptastic.corpus=off",
            "-Dproptastic.color=never");
    assertThat(PropertyTests.of(List.of(proptastic), List.of()).orElseThrow().jvmArgs())
        .startsWith("-Dproptastic.seed=0x0");
  }

  /// The person's proptastic seed is `0x` and hex digits; a bare number is one proptastic refuses,
  /// so the run's is given instead.
  @Test
  void thePersonsProptasticSeedIsHex() throws IOException {
    var proptastic = List.of(jar("p.jar", "io/proptastic/Proptastic.class"));

    var set = PropertyTests.of(proptastic, List.of("-Dproptastic.seed=0x2A"));
    var bare = PropertyTests.of(proptastic, List.of("-Dproptastic.seed=12345"));
    var wide = PropertyTests.of(proptastic, List.of("-Dproptastic.seed=0xffffffffffffffff"));

    assertThat(set.orElseThrow().seed()).contains("42");
    assertThat(set.orElseThrow().jvmArgs()).startsWith("-Dproptastic.seed=0x2a");
    assertThat(bare.orElseThrow().seed()).contains("0");
    assertThat(wide.orElseThrow().seed()).contains("18446744073709551615");
    assertThat(wide.orElseThrow().jvmArgs()).startsWith("-Dproptastic.seed=0xffffffffffffffff");
  }

  /// A jar in the test's directory holding one empty entry, `entry`.
  private Path jar(String name, String entry) throws IOException {
    var jar = dir.resolve(name);
    try (var out = new JarOutputStream(Files.newOutputStream(jar), new Manifest())) {
      out.putNextEntry(new ZipEntry(entry));
      out.closeEntry();
    }
    return jar;
  }
}
