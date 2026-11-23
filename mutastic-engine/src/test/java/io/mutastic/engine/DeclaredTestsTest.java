package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Whether a module's test classes declare a test, by their bytecode: helpers alone declare none,
/// and a test of any framework mutastic runs, or a base class from a library, may.
class DeclaredTestsTest {
  @TempDir Path work;

  /// The project's own class, which a helper may extend.
  private static final String MAIN =
      """
      package app;
      public class Pricing {}
      """;

  /// What the test classes refer to from the frameworks and libraries, stood in for.
  private static final List<Javac.Source> LIBRARIES =
      List.of(
          new Javac.Source("org.junit.Test", "package org.junit; public @interface Test {}"),
          new Javac.Source(
              "org.testng.annotations.Test",
              "package org.testng.annotations; public @interface Test {}"),
          new Javac.Source(
              "junit.framework.TestCase", "package junit.framework; public class TestCase {}"));

  @Test
  void helpersAloneDeclareNoTest() throws Exception {
    assertThat(
            declares(
                Map.of(
                    "app.Fixtures",
                    """
                    package app;
                    final class Fixtures {
                      static Pricing pricing() { return new Pricing(); }
                      static final class Clock implements java.util.function.Supplier<Long> {
                        public Long get() { return 0L; }
                      }
                    }
                    """,
                    "app.FakePricing",
                    "package app; class FakePricing extends Pricing {}",
                    "app.Order",
                    "package app; record Order(int amount) {}",
                    "app.Size",
                    "package app; enum Size { SMALL, LARGE }")))
        .isFalse();
  }

  @Test
  void aJunit5TestIsOneEvenDisabled() throws Exception {
    assertThat(
            declares(
                Map.of(
                    "app.PricingTest",
                    """
                    package app;
                    class PricingTest {
                      @org.junit.jupiter.api.Disabled
                      @org.junit.jupiter.api.Test
                      void prices() {}
                    }
                    """)))
        .isTrue();
  }

  @Test
  void aJunit4TestIsOne() throws Exception {
    assertThat(
            declares(
                Map.of(
                    "app.PricingTest",
                    "package app; public class PricingTest { @org.junit.Test public void p() {} }")))
        .isTrue();
  }

  @Test
  void aTestngClassAnnotatedAsATestIsOne() throws Exception {
    assertThat(
            declares(
                Map.of(
                    "app.PricingTest",
                    """
                    package app;
                    @org.testng.annotations.Test
                    public class PricingTest { public void prices() {} }
                    """)))
        .isTrue();
  }

  @Test
  void aTestInANestedClassIsOne() throws Exception {
    assertThat(
            declares(
                Map.of(
                    "app.PricingTest",
                    """
                    package app;
                    class PricingTest {
                      @org.junit.jupiter.api.Nested
                      class Discounts {
                        @org.junit.jupiter.api.Test
                        void prices() {}
                      }
                    }
                    """)))
        .isTrue();
  }

  /// A team's own `@Check`, which carries JUnit's `@Test`, is the test framework's too.
  @Test
  void aTestByAnAnnotationOfTheTeamsOwnIsOne() throws Exception {
    assertThat(
            declares(
                Map.of(
                    "app.Check",
                    """
                    package app;
                    @org.junit.jupiter.api.Test
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @interface Check {}
                    """,
                    "app.Prices",
                    "package app; class Prices { @Check void prices() {} }")))
        .isTrue();
  }

  /// JUnit 3's tests are methods named `test…`, found through the base class, with no annotation.
  @Test
  void aLibrarysBaseClassMayBringTests() throws Exception {
    assertThat(
            declares(
                Map.of(
                    "app.PricingTest",
                    """
                    package app;
                    public class PricingTest extends junit.framework.TestCase {
                      public void testPrices() {}
                    }
                    """)))
        .isTrue();
  }

  /// The test classes compiled into a test directory, beside the project's class in a directory of
  /// its own, and whether they declare a test.
  private boolean declares(Map<String, String> testSources) throws Exception {
    var sources = new java.util.ArrayList<Javac.Source>();
    sources.add(new Javac.Source("app.Pricing", MAIN));
    testSources.forEach((name, text) -> sources.add(new Javac.Source(name, text)));
    var tests = work.resolve("test");
    var main = work.resolve("main");
    for (var each : Javac.compile(sources, LIBRARIES).entrySet()) {
      var root = each.getKey().equals("app.Pricing") ? main : tests;
      var file = root.resolve(each.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, each.getValue());
    }
    return DeclaredTests.anyIn(List.of(tests), List.of(main));
  }
}
