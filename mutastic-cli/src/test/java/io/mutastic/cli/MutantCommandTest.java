package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.report.Invocation;
import io.mutastic.report.MutantCommand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// The commands the output offers for each mutant and the commands the CLI has stay in step: a
/// command offered that doesn't exist, or one added without being offered, fails here.
class MutantCommandTest {
  private static final String ID = "m855c8e3c";

  @Test
  void everyCommandTheOutputOffersParsesForTheSameProject() {
    var target = List.of("samples/pricing", ":api");
    var invocation = Invocation.of("mutastic", target);
    for (MutantCommand command : MutantCommand.values()) {
      var arguments = new ArrayList<>(command.arguments(ID));
      arguments.addAll(target);

      var parsed = Main.Command.parse(arguments.toArray(String[]::new));

      assertThat(parsed.only()).as(command.command(ID, invocation)).isEqualTo(Optional.of(ID));
      assertThat(parsed.directory())
          .as(command.command(ID, invocation))
          .isEqualTo(Path.of("samples/pricing"));
      assertThat(parsed.module()).as(command.command(ID, invocation)).isEqualTo(":api");
      assertThat(command.command(ID, invocation)).endsWith(" samples/pricing :api");
    }
  }

  @Test
  void aPathWithASpaceIsQuoted() {
    var invocation = Invocation.of("mutastic", List.of("my project"));

    assertThat(MutantCommand.REPRODUCE.command(ID, invocation))
        .isEqualTo("mutastic run --only=m855c8e3c 'my project'");
  }

  /// From the Maven plugin, each is its goal, with the options as `mutastic.` properties and the
  /// project as Maven names it: by the plugin's prefix when the build declares it, else by its
  /// coordinates.
  @Test
  void fromMavenEachIsAGoal() {
    var declared = Invocation.of("maven", List.of("my app", ":services/api"));
    var undeclared = Invocation.of("maven:io.mutastic:mutastic-maven-plugin:0.1.0", List.of());

    assertThat(MutantCommand.DONE_WHEN.command(ID, declared))
        .isEqualTo("mvn mutastic:verify -Dmutastic.mutant=m855c8e3c -f 'my app' -pl services/api");
    assertThat(MutantCommand.IGNORE.command(ID, undeclared))
        .isEqualTo(
            "mvn io.mutastic:mutastic-maven-plugin:0.1.0:ignore -Dmutastic.mutant=m855c8e3c"
                + " -Dmutastic.reason=\"<why>\"");
  }

  /// The plugin passes its goal and the `mutastic.` properties on as they are: each command offered
  /// from Maven parses to the same command as the command line's.
  @Test
  void everyCommandOfferedFromMavenParsesForTheSameProject() {
    var invocation = Invocation.of("maven", List.of("samples/pricing", ":api"));
    for (MutantCommand command : MutantCommand.values()) {
      var words = List.of(command.command(ID, invocation).split(" ", -1));
      var arguments = new ArrayList<String>();
      arguments.add(words.get(1).substring("mutastic:".length())); // the goal
      for (int index = 2; index < words.size(); index++) {
        switch (words.get(index)) {
          case "-f" -> arguments.add(words.get(++index));
          case "-pl" -> arguments.add(":" + words.get(++index));
          default -> arguments.add(words.get(index));
        }
      }

      var parsed = Main.Command.parse(arguments.toArray(String[]::new));
      var expected =
          Main.Command.parse(
              java.util.stream.Stream.concat(
                      command.arguments(ID).stream(), Stream.of("samples/pricing", ":api"))
                  .toArray(String[]::new));

      assertThat(parsed).as(command.command(ID, invocation)).isEqualTo(expected);
    }
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
    assertThat(MutantCommand.next(Invocation.of("mutastic", List.of("samples/pricing"))))
        .isEqualTo(
            "mutastic verify <id> samples/pricing · mutastic run --only=<id> samples/pricing"
                + " · mutastic explain <id> samples/pricing");
  }
}
