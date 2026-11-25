package io.mutastic.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuildArguments.Refused;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GradleArgumentsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "-Dorg.gradle.java.installations.paths=/jdk",
        "--offline",
        "-Pci",
        "-P ci=true",
        "--project-prop ci",
        "-x spotlessCheck",
        "-xtest",
        "-x :app:test",
        "-x check",
        "--exclude-task=:app:spotlessCheck",
        "--max-workers 2",
        "--init-script /x/init.gradle",
        "--no-daemon",
        "--no-configuration-cache",
        "-Dorg.gradle.parallel=true",
        "-Dorg.gradle.caching=true",
        "--rerun-tasks",
        "-s"
      })
  void anArgumentForTheBuildIsTaken(String arguments) {
    assertThat(GradleArguments.refused(List.of(arguments.split(" ")), "test")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--tests Foo",
        "--tests=Foo",
        "--continuous",
        "-t",
        "--dry-run",
        "-m",
        "-sm",
        "-p other",
        "--project-dir=other",
        "-pother",
        "-q",
        "--console=rich",
        "--configuration-cache",
        "-PmutasticOut=/x",
        "-D mutastic.x=1",
        "clean",
        "--offline clean",
        "-Dorg.gradle.configuration-cache=true",
        "-Dorg.gradle.unsafe.configuration-cache=true",
        "-D org.gradle.logging.level=quiet",
        "--system-prop=org.gradle.console=rich",
        "--configuration-cache=true",
        "-x compileJava",
        "-xcompileJava",
        "-x :app:compileJava",
        "-x cJ",
        "-x cTJ",
        "-x compJ",
        "-x classes",
        "-x testClasses",
        "-x tC",
        "-x integrationTestClasses",
        "-x compileIntegrationTestKotlin",
        "-x processResources",
        "-x :core:jar",
        "--exclude-task=classes",
        "--exclude-task :app:cJ"
      })
  void oneThatFightsMutasticIsRefused(String arguments) {
    assertThat(GradleArguments.refused(List.of(arguments.split(" ")), "integrationTest"))
        .isPresent();
  }

  /// A word that isn't an option may be the value of one before it.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SECRET",
        "--unknown SECRET",
        "--offline SECRET",
        "-Pmutastic=SECRET",
        "--tests=SECRET",
        "-pSECRET",
        "-Dorg.gradle.console=SECRET",
        "--system-prop org.gradle.console=SECRET"
      })
  void aRefusalNeverSaysAWordThatCouldBeAValue(String arguments) {
    assertThat(GradleArguments.refused(List.of(arguments.split(" ")), "test"))
        .hasValueSatisfying(refused -> assertThat(refused.toString()).doesNotContain("SECRET"));
  }

  /// The name, never a value, which may be a secret.
  @Test
  void aRefusalNamesTheArgumentWithoutItsValue() {
    assertThat(GradleArguments.refused(List.of("-Pmutastic.key=secret"), "test"))
        .contains(new Refused(0, "-P mutastic.key", "it's a setting of mutastic's own"));
    assertThat(GradleArguments.refused(List.of("--tests=Foo"), "test").map(Refused::name))
        .contains("--tests");
  }
}
