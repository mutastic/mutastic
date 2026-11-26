package io.mutastic.cli;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.regex.Matcher;

/// The rules from AGENTS.md that a compiler can't see: which layer may know about which, and the
/// regex calls that read a group by number. Run here because the CLI's tests see every module.
@AnalyzeClasses(packages = "io.mutastic", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  /// The api's build types, and their nested classes: what a build tool reads a build with
  /// (`BuildTool` and what it's given and answers), and the setup file the build plugins and the
  /// Maven extension write (`ProjectSetup`, into a `Project`).
  private static final String BUILD_TYPES =
      "io\\.mutastic\\.api\\.(BuildTool|BuildArguments|BuildErrors|BuildJava|Wrappers|Modules"
          + "|Project|ProjectSetup|TestSelection|RealPath|Nearest|Internal)(\\$.*)?";

  /// `Nearest` and its nested classes.
  private static final String NEAREST = "io\\.mutastic\\.api\\.Nearest(\\$.*)?";

  /// The worker runs inside the project's own JVM, on its classpath: nothing of the engine's goes
  /// with it.
  @ArchTest
  static final ArchRule workerStandsAlone =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.worker..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.mutastic.engine..", "io.mutastic.report..", "io.mutastic.cli..");

  /// The annotations a project puts in its own code bring nothing else of mutastic's with them, and
  /// mutastic reads them by simple name, so no part of it depends on them either.
  @ArchTest
  static final ArchRule annotationsStandAlone =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.annotations..")
          .should()
          .dependOnClassesThat()
          .resideOutsideOfPackages("io.mutastic.annotations..", "java..", "org.jspecify..");

  @ArchTest
  static final ArchRule nothingNeedsTheAnnotations =
      noClasses()
          .that()
          .resideOutsideOfPackage("io.mutastic.annotations..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.mutastic.annotations..");

  /// The result model and extension points that every module shares depend on nothing of ours.
  @ArchTest
  static final ArchRule apiStandsAlone =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.mutastic.engine..",
              "io.mutastic.report..",
              "io.mutastic.cli..",
              "io.mutastic.worker..");

  /// The engine finds and runs mutants; how they're shown is the report's.
  @ArchTest
  static final ArchRule engineKnowsNothingOfOutput =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.engine..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.mutastic.report..", "io.mutastic.output..", "io.mutastic.cli..");

  /// A part of the engine in a package of its own (`io.mutastic.engine.tests`, the test frameworks)
  /// stands on the api alone: the engine's core uses it, never the other way round.
  @ArchTest
  static final ArchRule enginePartsKnowNothingOfTheCore =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.engine.*..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.mutastic.engine");

  /// Nor does one part know another: each is the engine's to combine.
  @ArchTest
  static final ArchRule enginePartsStandApart =
      slices().matching("io.mutastic.engine.(*)..").should().notDependOnEachOther();

  /// The report renders a run from the result model, whoever ran it and however.
  @ArchTest
  static final ArchRule reportKnowsOnlyTheResultModel =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.report..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.mutastic.engine..", "io.mutastic.cli..");

  /// The output kit (`io.mutastic.output`: colour, glyphs, escaping and the fence, JSON and the
  /// events' envelope, progress, help's layout, remedies) knows nothing of mutastic, so a sibling
  /// tool can take it whole: no mutants, no cards, no scores, nothing of ours outside it.
  @ArchTest
  static final ArchRule outputKitStandsAlone =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.output..")
          .should()
          .dependOnClassesThat()
          .resideOutsideOfPackages("io.mutastic.output..", "java..", "org.jspecify..");

  /// The rule for a close name, which the api, the engine and the CLI all use, stands on the JDK
  /// alone, as the output kit does: it moves with the kit when a sibling tool takes it. It stays in
  /// the api until then, as moving it would change the api's public types.
  @ArchTest
  static final ArchRule closeNamesStandAlone =
      classes()
          .that()
          .haveNameMatching(NEAREST)
          .should()
          .onlyDependOnClassesThat(
              resideInAnyPackage("java..", "org.jspecify..")
                  .or(nameMatching(NEAREST))
                  .or(name("io.mutastic.api.Internal")));

  /// A build tool reads a project into the result model, and nothing else: no engine, no format.
  @ArchTest
  static final ArchRule buildToolsKnowOnlyTheApi =
      noClasses()
          .that()
          .resideInAnyPackage("io.mutastic.gradle..", "io.mutastic.maven..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.mutastic.engine..",
              "io.mutastic.report..",
              "io.mutastic.cli..",
              "io.mutastic.worker..");

  /// Build reading is carved out for the other tools in the family, as the output kit is: the build
  /// tools, the plugins and the Maven extension that write the setup file, and the api's build
  /// types know no mutants, results or scores. They stay where they are, as moving the api's types
  /// would change its public ones, until a sibling tool first reads a build.
  @ArchTest
  static final ArchRule buildReadingKnowsNoMutants =
      noClasses()
          .that()
          .resideInAnyPackage("io.mutastic.gradle..", "io.mutastic.maven..")
          .should()
          .dependOnClassesThat(
              resideInAPackage("io.mutastic.api").and(not(nameMatching(BUILD_TYPES))));

  /// And the build types are a set of their own: none reaches into the rest of the api.
  @ArchTest
  static final ArchRule buildTypesStandApart =
      classes()
          .that()
          .haveNameMatching(BUILD_TYPES)
          .should()
          .onlyDependOnClassesThat(
              resideOutsideOfPackage("io.mutastic..").or(nameMatching(BUILD_TYPES)));

  /// The CLI finds build tools as [io.mutastic.api.BuildTool]s: it names none of them, so a new one
  /// is a module on the classpath, not a change here.
  @ArchTest
  static final ArchRule nothingNamesABuildTool =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.mutastic.gradle..", "io.mutastic.maven..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.mutastic.gradle..", "io.mutastic.maven..");

  /// A group is read by its name: `group(2)` breaks silently when the pattern gains a group.
  @ArchTest
  static final ArchRule groupsReadByName =
      noClasses()
          .should()
          .callMethod(Matcher.class, "group", int.class)
          .orShould()
          .callMethod(Matcher.class, "start", int.class)
          .orShould()
          .callMethod(Matcher.class, "end", int.class);
}
