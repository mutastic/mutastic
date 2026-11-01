package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.mutastic.report.MutantCommand;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// The commands the output offers for each mutant and the commands the CLI has stay in step:
/// a command offered that doesn't exist, or one added without being offered, fails here.
class MutantCommandTest {
  private static final String ID = "m855c8e3c";

  @Test
  void everyCommandTheOutputOffersParses() {
    for (MutantCommand command : MutantCommand.values()) {
      var arguments = command.arguments(ID).toArray(String[]::new);

      assertThatCode(() -> Main.Command.parse(arguments)).as(command.command(ID)).doesNotThrowAnyException();
      assertThat(Main.Command.parse(arguments).only()).as(command.command(ID)).isEqualTo(Optional.of(ID));
    }
  }

  @Test
  void everyCommandForOneMutantIsOffered() {
    var offered = Arrays.stream(MutantCommand.values()).map(command -> command.arguments(ID).getFirst()).toList();

    for (Main.Subcommand subcommand : Main.Subcommand.values()) {
      if (subcommand.takesMutantId) {
        assertThat(offered).as("`mutastic %s` takes a mutant id, so the output must offer it".formatted(subcommand.word()))
            .contains(subcommand.word());
      }
    }
  }

  @Test
  void theSummaryOffersEachOneToo() {
    assertThat(MutantCommand.next()).isEqualTo("mutastic verify <id> · mutastic run --only=<id>");
  }
}
