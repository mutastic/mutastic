package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Help is written from the options' one declaration ([Options]): it names every option people use,
/// none the build plugins pass, and each command's help only that command's.
class HelpTest {
  @Test
  void helpNamesEveryOptionPeopleUseAndNoneThePluginsPass() {
    var help = Help.general("dev");

    for (Option option : Options.ALL) {
      if (option.group() == Option.Group.BUILD_PLUGINS) {
        assertThat(help).doesNotContain(option.name());
      } else {
        assertThat(help).contains("  " + option.synopsis() + " ");
      }
    }
    for (Main.Subcommand command : Main.Subcommand.values()) {
      assertThat(help).contains("mutastic " + command.synopsis);
    }
    assertThat(help).contains("Exit codes:").contains(Help.DOCS);
  }

  @Test
  void eachCommandsHelpHasOnlyItsOwnOptions() {
    assertThat(Help.of(Main.Subcommand.RUN)).contains("--threshold=N").doesNotContain("--reason");
    assertThat(Help.of(Main.Subcommand.IGNORE))
        .contains("--reason=WHY")
        .doesNotContain("--threshold");
  }

  /// The examples help gives are commands that parse.
  @Test
  void everyExampleParses() {
    for (Main.Subcommand command : Main.Subcommand.values()) {
      for (String example : command.examples) {
        // Quotes are the shell's: without them, a reason in words is still words.
        var args = example.replace("\"", "").replaceFirst("^mutastic ", "").split(" ", -1);

        assertThat(Main.Command.parse(args).subcommand()).as(example).isEqualTo(command);
      }
    }
  }

  @Test
  void helpAndTheVersionAreAnsweredBeforeAnyCommandRuns() {
    assertThat(run("--help")).startsWith("0 mutastic dev: finds the changes");
    assertThat(run("-h")).startsWith("0 mutastic dev: finds the changes");
    assertThat(run("run", "--help")).startsWith("0 Usage: mutastic run [DIR]");
    assertThat(run("help", "ignore")).startsWith("0 Usage: mutastic ignore ID");
    assertThat(run("--version")).isEqualTo("0 mutastic dev\n");
    assertThat(run()).startsWith("2 ");
  }

  /// The exit status, then what went to stdout.
  private static String run(String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int exit =
        Main.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            new Terminal(false, Map.of()));
    return exit + " " + out.toString(StandardCharsets.UTF_8);
  }
}
