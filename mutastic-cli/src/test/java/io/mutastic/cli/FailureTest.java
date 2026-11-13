package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// A failure as an agent reads it: the `run-failed` event, last on stdout in the agent and jsonl
/// formats, in the Rust edition's shape, and nowhere else.
class FailureTest {
  @Test
  void eachCommandSaysWhetherItRunsAsItIs() {
    var guessed =
        new Failure(
            Main.USAGE,
            "unknown-option",
            """
            mutastic: run has no option --formt. There's one called --format.
              mutastic run --format=agent
            """);
    var placeholder =
        new Failure(
            Main.USAGE,
            "no-project",
            """
            mutastic: x isn't a Gradle or Maven build.
              mutastic run <project directory> [:module]
            """);
    var exact =
        new Failure(
            Main.TESTS_FAIL,
            "red-baseline",
            """
            mutastic: a.BTest#c fails without any mutant, so mutastic can't judge one.
            Its output is in w.log. To run it alone:
              ./gradlew test --tests 'a.BTest.c'
            """);

    assertThat(guessed.help())
        .containsExactly(
            Map.of("applicability", "maybe", "command", "mutastic run --format=agent"));
    assertThat(placeholder.help().getFirst()).containsEntry("applicability", "placeholders");
    assertThat(exact.help())
        .containsExactly(
            Map.of("applicability", "exact", "command", "./gradlew test --tests 'a.BTest.c'"));
    assertThat(exact.message())
        .isEqualTo("a.BTest#c fails without any mutant, so mutastic can't judge one.");
  }

  @Test
  void theEventHasTheRustEditionsFieldsInItsOrder() {
    var failure = Failure.of(new Main.UsageException("x"), List.of("run", "no/such"), Map.of());

    var event = failure.event("no/such");

    assertThat(event.keySet())
        .containsExactly("code", "message", "where", "help", "exitCode", "rendered");
    assertThat(event).containsEntry("exitCode", Main.USAGE).containsEntry("code", "option-value");
    assertThat(event.get("where").toString()).isEqualTo("{directory=no/such, path=null}");
  }

  /// Only for an agent's or a program's stdout, and never for a hook, whose stdout is its answer.
  @Test
  void theEventIsOnStdoutOnlyForAgentsAndPrograms() {
    var agent = Samples.mutastic(Map.of("MUTASTIC_AGENT", "1"), "rnu");
    var person = Samples.mutastic("rnu");
    var hook = Samples.mutastic(Map.of("MUTASTIC_AGENT", "1"), "gate", "check", "--hook=nope");

    assertThat(agent.out())
        .startsWith(
            "{\"schema\":\"mutastic.events/v1\",\"edition\":\"java\","
                + "\"reason\":\"run-failed\",\"code\":\"unknown-command\"");
    assertThat(agent.err()).startsWith("mutastic: mutastic has no command rnu.");
    assertThat(person.out()).isEmpty();
    assertThat(hook.out()).isEmpty();
  }
}
