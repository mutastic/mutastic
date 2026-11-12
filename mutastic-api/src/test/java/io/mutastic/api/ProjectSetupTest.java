package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectSetupTest {
  /// A coverage tool's agent is left out however the build names its jar, JaCoCo's under Gradle and
  /// Maven, Kover's and IntelliJ's, on any platform; another agent, or an argument that only
  /// mentions one, is kept.
  @Test
  void aCoverageAgentIsKnownByItsJarsName() {
    assertThat(
            ProjectSetup.coverageAgent(
                "-javaagent:/home/u/.gradle/jacoco/jacocoagent.jar=destfile=x"))
        .isTrue();
    assertThat(
            ProjectSetup.coverageAgent(
                "-javaagent:/home/u/.m2/repository/org/jacoco/org.jacoco.agent/0.8.12/"
                    + "org.jacoco.agent-0.8.12-runtime.jar=destfile=/p/target/jacoco.exec"))
        .isTrue();
    assertThat(ProjectSetup.coverageAgent("-javaagent:C:\\m2\\org.jacoco.agent-0.8.12-runtime.jar"))
        .isTrue();
    assertThat(
            ProjectSetup.coverageAgent(
                "-javaagent:/p/build/kover/kover-jvm-agent-0.9.1.jar=file:/p/build/tmp/kover.args"))
        .isTrue();
    assertThat(ProjectSetup.coverageAgent("-javaagent:/c/intellij-coverage-agent-1.0.765.jar"))
        .isTrue();
    assertThat(ProjectSetup.coverageAgent("-javaagent:/libs/byte-buddy-agent.jar")).isFalse();
    assertThat(ProjectSetup.coverageAgent("-Djacoco.destfile=target/jacoco.exec")).isFalse();
    assertThat(ProjectSetup.coverageAgent("-javaagent:/jacoco/tools/other.jar=jacocoagent"))
        .isFalse();
  }
}
