package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectSetupTest {
  /// JaCoCo's agent is left out however the build names its jar, Gradle's and Maven's, on any
  /// platform; another agent, or an argument that only mentions JaCoCo, is kept.
  @Test
  void jacocosAgentIsKnownByEitherBuildsJarName() {
    assertThat(ProjectSetup.jacoco("-javaagent:/home/u/.gradle/jacoco/jacocoagent.jar=destfile=x"))
        .isTrue();
    assertThat(
            ProjectSetup.jacoco(
                "-javaagent:/home/u/.m2/repository/org/jacoco/org.jacoco.agent/0.8.12/"
                    + "org.jacoco.agent-0.8.12-runtime.jar=destfile=/p/target/jacoco.exec"))
        .isTrue();
    assertThat(ProjectSetup.jacoco("-javaagent:C:\\m2\\org.jacoco.agent-0.8.12-runtime.jar"))
        .isTrue();
    assertThat(ProjectSetup.jacoco("-javaagent:/libs/byte-buddy-agent.jar")).isFalse();
    assertThat(ProjectSetup.jacoco("-Djacoco.destfile=target/jacoco.exec")).isFalse();
    assertThat(ProjectSetup.jacoco("-javaagent:/jacoco/tools/other.jar=jacocoagent")).isFalse();
  }
}
