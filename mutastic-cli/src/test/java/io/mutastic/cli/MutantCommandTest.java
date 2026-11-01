package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.report.MutantCommand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// The commands the output offers for each mutant and the commands the CLI has stay in step: a
/// command offered that doesn't exist, or one added without being offered, fails here.
class MutantCommandTest {
  private static final String ID = "m855c8e3c";

  @Test
  void everyCommandTheOutputOffersParsesForTheSameProject() {
    var target = List.of("samples/pricing", ":api");
    for (MutantCommand command : MutantCommand.values()) {
      var arguments = new ArrayList<>(command.arguments(ID));
      arguments.addAll(target);

      var parsed = Main.Command.parse(arguments.toArray(String[]::new));

      assertThat(parsed.only()).as(command.command(ID, target)).isEqualTo(Optional.of(ID));
      assertThat(parsed.directory())
          .as(command.command(ID, target))
          .isEqualTo(Path.of("samples/pricing"));
      assertThat(parsed.module()).as(command.command(ID, target)).isEqualTo(":api");
      assertThat(command.command(ID, target)).endsWith(" samples/pricing :api");
    }
  }

  @Test
  void aPathWithASpaceIsQuoted() {
    assertThat(MutantCommand.REPRODUCE.command(ID, List.of("my project")))
        .isEqualTo("mutastic run --only=m855c8e3c 'my project'");
  }

  @Test
  void theTargetIsTheProjectAsTypedFromHere() {
    var here = Path.of("/code");

    assertThat(Main.target(here, Path.of("/code"), ":")).isEmpty();
    assertThat(Main.target(here, Path.of("/code/samples/pricing"), ":"))
        .containsExactly("samples/pricing");
    assertThat(Main.target(here, Path.of("/elsewhere/app"), ":api"))
        .containsExactly("/elsewhere/app", ":api");
  }

  @Test
  void everyCommandForOneMutantIsOffered() {
    var offered =
        Arrays.stream(MutantCommand.values())
            .map(command -> command.arguments(ID).getFirst())
            .toList();

    for (Main.Subcommand subcommand : Main.Subcommand.values()) {
      if (subcommand.takesMutantId) {
        assertThat(offered)
            .as(
                "`mutastic %s` takes a mutant id, so the output must offer it"
                    .formatted(subcommand.word()))
            .contains(subcommand.word());
      }
    }
  }

  @Test
  void theSummaryOffersTheWaysToAFixAndExplain() {
    assertThat(MutantCommand.next(List.of("samples/pricing")))
        .isEqualTo(
            "mutastic verify <id> samples/pricing · mutastic run --only=<id> samples/pricing"
                + " · mutastic explain <id> samples/pricing");
  }
}
