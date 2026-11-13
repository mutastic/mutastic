package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// Which progress a run shows, as `PROGRESS.md` decides it.
class ProgressChoiceTest {
  private static final Optional<String> NOT_GIVEN = Optional.empty();

  /// stderr a terminal, as the start script says.
  private static Terminal watched(Map<String, String> more) {
    var env = new java.util.HashMap<>(more);
    env.put("MUTASTIC_STDERR_TTY", "1");
    return new Terminal(false, env);
  }

  @Test
  void aTerminalGetsTheLiveLineAndAnythingElseCounterLines() {
    assertThat(ProgressChoice.mode(NOT_GIVEN, "full", watched(Map.of())))
        .isEqualTo(Progress.Mode.BAR);
    assertThat(ProgressChoice.mode(NOT_GIVEN, "full", new Terminal(false, Map.of())))
        .isEqualTo(Progress.Mode.COUNTER);
  }

  @Test
  void stderrDecidesNotStdinAndStdout() {
    var stdoutRedirected = new Terminal(false, Map.of("MUTASTIC_STDERR_TTY", "1"));
    var stderrRedirected = new Terminal(true, Map.of("MUTASTIC_STDERR_TTY", "0"));

    assertThat(stdoutRedirected.stderrIsTerminal()).isTrue();
    assertThat(stderrRedirected.stderrIsTerminal()).isFalse();
    assertThat(new Terminal(true, Map.of()).stderrIsTerminal()).isTrue();
    assertThat(new Terminal(false, Map.of()).stderrIsTerminal()).isFalse();
  }

  @Test
  void aDumbTerminalCiAndAgentsGetCounterLines() {
    for (var env :
        java.util.List.of(
            Map.of("TERM", "dumb"),
            Map.of("CI", "true"),
            Map.of("CI", ""),
            Map.of("GITHUB_ACTIONS", "true"),
            Map.of("JENKINS_URL", "http://ci"),
            Map.of("MUTASTIC_AGENT", "1"),
            Map.of("CLAUDECODE", "1"),
            Map.of("CODEX_SANDBOX", "seatbelt"))) {
      assertThat(ProgressChoice.mode(NOT_GIVEN, "full", watched(env)))
          .as(env.toString())
          .isEqualTo(Progress.Mode.COUNTER);
    }
    assertThat(ProgressChoice.mode(NOT_GIVEN, "full", watched(Map.of("CI", "false"))))
        .isEqualTo(Progress.Mode.BAR);
    assertThat(ProgressChoice.mode(NOT_GIVEN, "full", watched(Map.of("CI", "0"))))
        .isEqualTo(Progress.Mode.BAR);
  }

  @Test
  void agentAndJsonlGetCounterLinesUnlessToldOtherwise() {
    assertThat(ProgressChoice.mode(NOT_GIVEN, "jsonl", watched(Map.of())))
        .isEqualTo(Progress.Mode.COUNTER);
    assertThat(ProgressChoice.mode(NOT_GIVEN, "agent", watched(Map.of())))
        .isEqualTo(Progress.Mode.COUNTER);
    assertThat(ProgressChoice.mode(Optional.of("none"), "jsonl", watched(Map.of())))
        .isEqualTo(Progress.Mode.NONE);
    assertThat(
            ProgressChoice.mode(NOT_GIVEN, "jsonl", watched(Map.of("MUTASTIC_PROGRESS", "none"))))
        .isEqualTo(Progress.Mode.NONE);
  }

  @Test
  void theOptionOverTheEnvironment() {
    var env = watched(Map.of("MUTASTIC_PROGRESS", "none"));

    assertThat(ProgressChoice.mode(NOT_GIVEN, "full", env)).isEqualTo(Progress.Mode.NONE);
    assertThat(ProgressChoice.mode(Optional.of("counter"), "full", env))
        .isEqualTo(Progress.Mode.COUNTER);
    assertThat(ProgressChoice.mode(Optional.of("auto"), "full", env)).isEqualTo(Progress.Mode.BAR);
    assertThatThrownBy(
            () ->
                ProgressChoice.mode(
                    NOT_GIVEN, "full", watched(Map.of("MUTASTIC_PROGRESS", "loud"))))
        .hasMessageContaining("MUTASTIC_PROGRESS");
  }
}
