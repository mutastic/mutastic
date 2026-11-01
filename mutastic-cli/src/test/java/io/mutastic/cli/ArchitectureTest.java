package io.mutastic.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.regex.Matcher;

/// The rules from AGENTS.md that a compiler can't see: which layer may know about which, and the
/// regex calls that read a group by number. Run here because the CLI's tests see every module.
@AnalyzeClasses(packages = "io.mutastic", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
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
          .resideInAnyPackage("io.mutastic.report..", "io.mutastic.cli..");

  /// The report renders a run from the result model, whoever ran it and however.
  @ArchTest
  static final ArchRule reportKnowsOnlyTheResultModel =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.report..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.mutastic.engine..", "io.mutastic.cli..");

  /// A build tool reads a project into the result model, and nothing else: no engine, no format.
  @ArchTest
  static final ArchRule buildToolsKnowOnlyTheApi =
      noClasses()
          .that()
          .resideInAPackage("io.mutastic.gradle..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.mutastic.engine..",
              "io.mutastic.report..",
              "io.mutastic.cli..",
              "io.mutastic.worker..");

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
