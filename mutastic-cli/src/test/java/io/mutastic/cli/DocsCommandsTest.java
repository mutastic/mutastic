package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

/// Every command the docs show is one the CLI takes: each `mutastic …`, `mvn …mutastic:…` and
/// `./gradlew …mutastic…` line in the docs' code blocks parses, the build plugins' forms read as
/// the plugins pass them, and so does each `mutastic …` a CI job runs, from a GitHub Actions step
/// or a GitLab job's script. The website's copy once showed options that didn't exist; this keeps
/// the docs from doing the same.
class DocsCommandsTest {
  private static final Path DOCS = Path.of(System.getProperty("mutastic.docs"));

  /// The code blocks commands are typed in; a Kotlin `mutastic { }` block isn't one.
  private static final List<String> SHELLS = List.of("sh", "bash", "shell", "console", "");

  /// The code blocks a workflow is in: a GitHub Actions job's steps and a GitLab job's scripts run
  /// commands too.
  private static final List<String> YAML = List.of("yaml", "yml");

  /// The keys a CI job's command follows on the same line: a GitHub Actions step's `run:`, a GitLab
  /// job's `script:`. A command on a line of its own, a list item or in a `run: |` block, has none.
  private static final List<String> COMMAND_KEYS =
      List.of("run:", "script:", "before_script:", "after_script:");

  /// A GitHub Actions expression, `${{ matrix.share }}`, or a shell variable, `$CI_NODE_INDEX`: it
  /// stands for a value when the job runs.
  private static final Pattern EXPRESSION =
      Pattern.compile("\\$\\{\\{[^}]*}}|\\$\\{?(?<variable>[A-Za-z_][A-Za-z0-9_]*)}?");

  /// What help and the version answer before a command is parsed.
  private static final List<String> ANSWERED = List.of("--help", "-h", "--version", "help");

  /// What ends a command in a shell line, another command after it.
  private static final List<String> END_OF_COMMAND = List.of("||", "&&", "|", ";");

  @Test
  void everyCommandTheDocsShowParses() throws IOException {
    var failures = new ArrayList<String>();
    var checked = 0;
    for (Shown shown : commands()) {
      var args = arguments(shown.line());
      if (args.isEmpty() || ANSWERED.contains(args.getFirst())) {
        continue;
      }
      checked++;
      try {
        Main.Command.parse(args.toArray(String[]::new));
      } catch (Main.UsageException e) {
        failures.add("%s: %s (%s)".formatted(shown.where(), shown.line(), e.getMessage()));
      }
    }

    assertThat(failures).isEmpty();
    assertThat(checked).as("commands found in the docs").isGreaterThanOrEqualTo(5);
  }

  /// Every YAML block the docs show is YAML: a `mutastic.yaml` or a CI workflow copied from the
  /// docs works as it's shown.
  @Test
  void everyYamlBlockTheDocsShowParses() throws IOException {
    var yaml = new YAMLMapper();
    var failures = new ArrayList<String>();
    var blocks = yamlBlocks();
    for (Shown block : blocks) {
      try {
        yaml.readTree(block.line());
      } catch (RuntimeException e) {
        failures.add("%s: %s".formatted(block.where(), e.getMessage()));
      }
    }

    assertThat(failures).isEmpty();
    assertThat(blocks).as("YAML blocks found in the docs").hasSizeGreaterThanOrEqualTo(5);
  }

  /// The reference's examples, written from the catalogue for each runner, parse too: each option's
  /// and each command's, from Gradle, Maven and the command line.
  @Test
  void everyExampleTheReferenceShowsParsesInEveryRunner() {
    var commandLines = new ArrayList<String>();
    for (Option option : Options.ALL) {
      if (!option.example().isEmpty()) {
        option.commands().forEach(command -> commandLines.add(option.exampleFor(command)));
      }
    }
    for (Main.Subcommand command : Main.Subcommand.values()) {
      commandLines.addAll(command.examples);
    }
    for (String commandLine : commandLines) {
      var forms =
          Runners.byThePlugins(commandLine)
              ? List.of(commandLine, Runners.gradle(commandLine), Runners.maven(commandLine))
              : List.of(commandLine);
      for (String line : forms) {
        var args = arguments(line);

        assertThat(Main.Command.parse(args.toArray(String[]::new)))
            .as(line)
            .isEqualTo(Main.Command.parse(arguments(commandLine).toArray(String[]::new)));
      }
    }
  }

  /// An option's example on a command's page is that command, so `gate close`'s page doesn't show
  /// `gate check`, and `verify`'s gives it a mutant.
  @Test
  void anOptionsExampleOnACommandsPageIsThatCommand() {
    for (Option option : Options.ALL) {
      for (Main.Subcommand command : option.commands()) {
        if (!option.example().isEmpty()) {
          var example = option.exampleFor(command);

          assertThat(Main.Subcommand.in(example)).as(example).contains(command);
          assertThat(Main.Command.parse(arguments(example).toArray(String[]::new)).subcommand())
              .as(example)
              .isEqualTo(command);
        }
      }
    }
    var hook =
        Options.ALL.stream()
            .filter(option -> option.name().equals("--hook"))
            .findFirst()
            .orElseThrow();
    var workers =
        Options.ALL.stream()
            .filter(option -> option.name().equals("--workers"))
            .findFirst()
            .orElseThrow();
    assertThat(hook.exampleFor(Main.Subcommand.GATE_CLOSE))
        .isEqualTo("mutastic gate close --hook=claude");
    assertThat(workers.exampleFor(Main.Subcommand.VERIFY))
        .isEqualTo("mutastic verify m855c8e3c --workers=2");
  }

  /// A command line from a code block, and the file and line it's on.
  private record Shown(String where, String line) {}

  private static List<Shown> commands() throws IOException {
    var shown = new ArrayList<Shown>();
    try (Stream<Path> files = Files.walk(DOCS)) {
      for (Path file : files.filter(DocsCommandsTest::isPage).sorted().toList()) {
        var lines = Files.readAllLines(file);
        boolean inCode = false;
        boolean inShell = false;
        boolean inYaml = false;
        for (int index = 0; index < lines.size(); index++) {
          var line = lines.get(index).strip();
          if (line.startsWith("```")) {
            inCode = !inCode;
            inShell = inCode && SHELLS.contains(line.substring(3).strip());
            inYaml = inCode && YAML.contains(language(line));
          } else if (inShell && isCommand(line)) {
            shown.add(new Shown(DOCS.relativize(file) + ":" + (index + 1), line));
          } else if (inYaml && isCommand(stepCommand(line))) {
            shown.add(new Shown(DOCS.relativize(file) + ":" + (index + 1), stepCommand(line)));
          }
        }
      }
    }
    return shown;
  }

  /// Each YAML block in the docs, as its text, and the file and line it starts on.
  private static List<Shown> yamlBlocks() throws IOException {
    var blocks = new ArrayList<Shown>();
    try (Stream<Path> files = Files.walk(DOCS)) {
      for (Path file : files.filter(DocsCommandsTest::isPage).sorted().toList()) {
        var lines = Files.readAllLines(file);
        var block = new StringBuilder();
        boolean inCode = false;
        boolean inYaml = false;
        var start = 0;
        for (int index = 0; index < lines.size(); index++) {
          var line = lines.get(index);
          var fence = line.strip();
          if (fence.startsWith("```")) {
            if (inYaml) {
              blocks.add(new Shown(DOCS.relativize(file) + ":" + start, block.toString()));
            }
            inCode = !inCode;
            inYaml = inCode && YAML.contains(language(fence));
            block.setLength(0);
            start = index + 1;
          } else if (inYaml) {
            block.append(line).append('\n');
          }
        }
      }
    }
    return blocks;
  }

  /// `yaml` for a block opened with ```` ```yaml title="mutastic.yaml" ````.
  private static String language(String fence) {
    var info = fence.substring(3).strip();
    var space = info.indexOf(' ');
    return space < 0 ? info : info.substring(0, space);
  }

  /// The command a workflow's line runs, `- run: mutastic run --shard=${{ matrix.share }}/4` →
  /// `mutastic run --shard=1/4`, `- mutastic run --shard=$CI_NODE_INDEX/$CI_NODE_TOTAL` in a GitLab
  /// job's `script:` → `mutastic run --shard=1/1`, or the line itself in a `run: |` block. An
  /// expression or a variable is a value once the job runs, so a value stands in for it.
  private static String stepCommand(String line) {
    var step = line.startsWith("- ") ? line.substring(2).strip() : line;
    var command =
        COMMAND_KEYS.stream()
            .filter(step::startsWith)
            .findFirst()
            .map(key -> step.substring(key.length()).strip())
            .orElse(step);
    return EXPRESSION.matcher(command).replaceAll("1");
  }

  private static boolean isPage(Path file) {
    var name = file.getFileName().toString();
    return name.endsWith(".md") || name.endsWith(".mdx");
  }

  private static boolean isCommand(String line) {
    return line.startsWith("mutastic ")
        || (line.startsWith("mvn ") && line.contains("mutastic:"))
        || ((line.startsWith("./gradlew ") || line.startsWith("gradle "))
            && line.contains("mutastic"));
  }

  /// The arguments the CLI gets for `line`: as typed for `mutastic`, or as the plugins pass a
  /// goal's or task's.
  private static List<String> arguments(String line) {
    var words = words(line);
    return switch (words.getFirst()) {
      case "mutastic" -> words.subList(1, words.size());
      case "mvn" -> fromBuild(words, "mutastic:", "-pl", "-f");
      default -> fromBuild(words, "mutastic", "", "-p");
    };
  }

  /// `mvn mutastic:verify -Dmutastic.mutant=m… -pl core` → `verify -Dmutastic.mutant=m… :core`;
  /// `./gradlew :core:mutasticVerify -Pmutastic.mutant=m…` → the same. Other build options are the
  /// build's, not mutastic's, and left out.
  private static List<String> fromBuild(
      List<String> words, String marker, String moduleFlag, String directoryFlag) {
    var arguments = new ArrayList<String>();
    var module = "";
    var directory = "";
    for (int index = 1; index < words.size(); index++) {
      var word = words.get(index);
      if (word.contains(marker) && arguments.isEmpty()) {
        var task = word.substring(word.lastIndexOf(marker) + marker.length());
        arguments.add(Character.toLowerCase(task.charAt(0)) + task.substring(1));
        module = word.contains(":mutastic") ? word.substring(0, word.lastIndexOf(":mutastic")) : "";
      } else if (word.equals(moduleFlag)) {
        module = ":" + words.get(++index);
      } else if (word.equals(directoryFlag)) {
        directory = words.get(++index);
      } else if (word.startsWith("-Dmutastic.") || word.startsWith("-Pmutastic.")) {
        arguments.add(word);
      }
    }
    if (!directory.isEmpty()) {
      arguments.add(directory);
    }
    if (!module.isEmpty()) {
      arguments.add(module);
    }
    return arguments;
  }

  /// `line` split as a shell would: on spaces, but not inside double quotes, which it drops.
  static List<String> words(String line) {
    var words = new ArrayList<String>();
    var word = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (character == ' ' && !quoted) {
        if (word.length() > 0) {
          words.add(word.toString());
          word.setLength(0);
        }
      } else {
        word.append(character);
      }
    }
    if (word.length() > 0) {
      words.add(word.toString());
    }
    return command(words);
  }

  /// The command alone: up to a comment (`# the head of the queue`) or the next command (`||
  /// send-back`).
  private static List<String> command(List<String> words) {
    for (int index = 0; index < words.size(); index++) {
      var word = words.get(index);
      if (word.startsWith("#") || END_OF_COMMAND.contains(word)) {
        return words.subList(0, index);
      }
    }
    return words;
  }
}
