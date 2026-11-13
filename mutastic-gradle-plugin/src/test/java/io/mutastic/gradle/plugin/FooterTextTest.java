package io.mutastic.gradle.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// What Gradle's progress line says of each live event, as `PROGRESS.md` writes the footer.
class FooterTextTest {
  private static final String HEAD = "{\"schema\":\"mutastic.events/v1\",\"edition\":\"java\",";

  @Test
  void theMutantsSayHowFarTheyAreWhatsMissedAndWhatsLeft() {
    assertThat(
            FooterText.of(
                HEAD
                    + "\"reason\":\"progress\",\"phase\":\"mutants\",\"elapsedMs\":1450000,"
                    + "\"done\":2871,\"total\":5525,\"weighted\":0.52,\"leftMs\":1260000,"
                    + "\"caught\":2610,\"missed\":41,\"notCovered\":214,\"timedOut\":6,"
                    + "\"running\":8}"))
        .hasValue("mutants 2871/5525 · 52% · ✗ 41 missed · ~21m left");
    assertThat(
            FooterText.of(
                HEAD
                    + "\"reason\":\"progress\",\"phase\":\"mutants\",\"elapsedMs\":2000,"
                    + "\"done\":3,\"total\":11,\"caught\":3,\"missed\":0,\"notCovered\":0,"
                    + "\"timedOut\":0,\"running\":2}"))
        .hasValue("mutants 3/11 · ✗ 0 missed");
  }

  @Test
  void eachPhaseSaysWhatItKnows() {
    assertThat(FooterText.of(HEAD + "\"reason\":\"phase-started\",\"phase\":\"building\"}"))
        .hasValue("building");
    assertThat(
            FooterText.of(
                HEAD
                    + "\"reason\":\"progress\",\"phase\":\"coverage\",\"elapsedMs\":60000,"
                    + "\"done\":18,\"total\":181}"))
        .hasValue("coverage 18/181 tests");
    assertThat(
            FooterText.of(
                HEAD + "\"reason\":\"progress\",\"phase\":\"checking\",\"elapsedMs\":30000}"))
        .hasValue("checking");
    assertThat(
            FooterText.of(
                HEAD + "\"reason\":\"phase-finished\",\"phase\":\"building\",\"durationMs\":1}"))
        .isEmpty();
    assertThat(FooterText.of("not an event")).isEmpty();
  }

  @Test
  void whatsLeftIsSteadyAsTheLiveLineSaysIt() {
    assertThat(FooterText.left(1_260_000)).isEqualTo("~21m left");
    assertThat(FooterText.left(254_000)).isEqualTo("~4m10s left");
    assertThat(FooterText.left(41_000)).isEqualTo("~40s left");
    assertThat(FooterText.left(1_000)).isEqualTo("~5s left");
  }
}
