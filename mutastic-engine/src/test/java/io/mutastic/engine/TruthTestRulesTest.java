package io.mutastic.engine;

import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.invoke.MethodHandles;

/// What a truth test may do itself. Mutation testing runs each truth test again for every mutant
/// that reaches it, so the costly parts go through the helpers that keep them: [Javac] compiles in
/// memory and keeps what it compiled, [ClaimFixture] and [Kept] keep what a class gives.
@AnalyzeClasses(
    packages = "io.mutastic.engine",
    importOptions = ImportOption.OnlyIncludeTests.class)
class TruthTestRulesTest {
  /// The truth tests, and the classes nested in them.
  private static final String TRUTH_TESTS = "io\\.mutastic\\.engine\\.\\w+TruthTest(\\$.*)?";

  /// The loaders four truth tests defined their own classes with before this rule: each puts a
  /// mutant's bytes in a loader of its own.
  private static final String OWN_LOADERS =
      "io\\.mutastic\\.engine\\.(Card|Discard|Return|Store)TruthTest\\$OneClass";

  @ArchTest
  static final ArchRule compiledThroughJavac =
      noClasses()
          .that()
          .haveNameMatching(TRUTH_TESTS)
          .should()
          .dependOnClassesThat()
          .resideInAPackage("javax.tools..")
          .because("Javac compiles a text once, in memory, however many mutants run the test");

  @ArchTest
  static final ArchRule noTemporaryDirectories =
      noClasses()
          .that()
          .haveNameMatching(TRUTH_TESTS)
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.junit.jupiter.api.io.TempDir")
          .because(
              "a truth test runs again for every mutant that reaches it: what it keeps goes under"
                  + " the module's build, where the next run finds it");

  @ArchTest
  static final ArchRule classesDefinedThroughTheHelpers =
      noClasses()
          .that()
          .haveNameMatching(TRUTH_TESTS)
          .and()
          .haveNameNotMatching(OWN_LOADERS)
          .should()
          .beAssignableTo(ClassLoader.class)
          .orShould()
          .callMethodWhere(
              target(declaredIn(MethodHandles.Lookup.class).and(nameStartingWith("define"))))
          .because(
              "a class a truth test defines itself is one more through the JVM for every mutant,"
                  + " kept by nothing");
}
