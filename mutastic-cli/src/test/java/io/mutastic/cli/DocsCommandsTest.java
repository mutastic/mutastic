package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Every command the docs show is one the CLI takes: each `mutastic …`, `mvn …mutastic:…` and
/// `./gradlew …mutastic…` line in the docs' code blocks parses, the build plugins' forms read as
/// the plugins pass them. The website's copy once showed options that didn't exist; this keeps the
/// docs from doing the same.
class DocsCommandsTest {
  private static final Path DOCS = Path.of(System.getProperty("mutastic.docs"));

  /// The code blocks commands are typed in; a Kotlin `mutastic { }` block isn't one.
  private static final List<String> SHELLS = List.of("sh", "bash", "shell", "console", "");

  /// What help and the version answer before a command is parsed.
  private static final List<String> ANSWERED = List.of("--help", "-h", "--version", "help");

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

  /// The reference's examples, written from the catalogue for each runner, parse too: each option's
  /// and each command's, from Gradle, Maven and the command line.
  @Test
  void everyExampleTheReferenceShowsParsesInEveryRunner() {
    var commandLines = new ArrayList<String>();
    for (Option option : Options.ALL) {
      if (!option.example().isEmpty()) {
        commandLines.add(option.example());
      }
    }
    for (Main.Subcommand command : Main.Subcommand.values()) {
      commandLines.addAll(command.examples);
    }
    for (String commandLine : commandLines) {
      for (String line :
          List.of(commandLine, Runners.gradle(commandLine), Runners.maven(commandLine))) {
        var args = arguments(line);

        assertThat(Main.Command.parse(args.toArray(String[]::new)))
            .as(line)
            .isEqualTo(Main.Command.parse(arguments(commandLine).toArray(String[]::new)));
      }
    }
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
        for (int index = 0; index < lines.size(); index++) {
          var line = lines.get(index).strip();
          if (line.startsWith("```")) {
            inCode = !inCode;
            inShell = inCode && SHELLS.contains(line.substring(3).strip());
          } else if (inShell && isCommand(line)) {
            shown.add(new Shown(DOCS.relativize(file) + ":" + (index + 1), line));
          }
        }
      }
    }
    return shown;
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
  private static List<String> words(String line) {
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
    return words;
  }
}
