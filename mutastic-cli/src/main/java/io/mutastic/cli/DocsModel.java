package io.mutastic.cli;

import io.mutastic.cli.Option.Group;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/// The commands and options as the docs reference reads them, `commands.json`: written from the
/// same declarations as the parser and `--help` ([Options], the commands, [Help]'s lists), by
/// `./gradlew :mutastic-cli:exportDocsModel`. The build fails while the committed copy differs.
///
/// Each option is spelled as each runner writes it: `--threshold=N` on the command line,
/// `-Dmutastic.threshold` or `<threshold>` for Maven, `-Pmutastic.threshold` or `threshold = …` for
/// Gradle.
final class DocsModel {
  private DocsModel() {}

  /// The version of this file's shape, for the docs site to check.
  static final String SCHEMA = "mutastic.commands/v1";

  public static void main(String[] args) throws IOException {
    var file = Path.of(args[0]);
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.writeString(file, json(), StandardCharsets.UTF_8);
  }

  static String json() {
    var out = new StringBuilder();
    out.append("{\n");
    out.append("  \"schema\": ").append(quoted(SCHEMA)).append(",\n");
    out.append("  \"docs\": ").append(quoted(Help.DOCS)).append(",\n");
    out.append("  \"commands\": [\n").append(commands()).append("\n  ],\n");
    out.append("  \"options\": [\n").append(options()).append("\n  ],\n");
    out.append("  \"environment\": [\n").append(entries(Help.ENVIRONMENT)).append("\n  ],\n");
    out.append("  \"exitCodes\": [\n").append(entries(Help.EXIT_CODES)).append("\n  ]\n");
    return out.append("}\n").toString();
  }

  private static String commands() {
    var commands = new ArrayList<String>();
    for (Main.Subcommand command : Main.Subcommand.values()) {
      var options = Options.shownFor(command).stream().map(Option::name).toList();
      commands.add(
          object(
              List.of(
                  field("name", quoted(command.word())),
                  field("page", quoted(command.page())),
                  field("synopsis", quoted("mutastic " + command.synopsis)),
                  field("summary", quoted(command.summary)),
                  field("detail", quoted(command.detail)),
                  field("exits", quoted(command.exits)),
                  field("takesMutantId", String.valueOf(command.takesMutantId)),
                  field("examples", examples(command.examples)),
                  field("options", strings(options)))));
    }
    return String.join(",\n", commands);
  }

  private static String options() {
    return Options.ALL.stream()
        .filter(option -> option.group() != Group.BUILD_PLUGINS)
        .map(DocsModel::option)
        .collect(Collectors.joining(",\n"));
  }

  private static String option(Option option) {
    var commands = option.commands().stream().map(Main.Subcommand::word).sorted().toList();
    return object(
        List.of(
            field("name", quoted(option.name())),
            field("synopsis", quoted(option.synopsis())),
            field("kind", quoted(option.kind().name().toLowerCase(Locale.ROOT))),
            field("group", quoted(option.group().heading)),
            field("value", quoted(option.value())),
            field("choices", strings(option.choices())),
            field("help", quoted(option.help())),
            field("commands", strings(commands)),
            field("environment", quoted(option.environment())),
            field("maven", runner(option, "-Dmutastic.", "<%s>%s</%s>")),
            field("gradle", runner(option, "-Pmutastic.", "%s = %s")),
            field("examples", optionExamples(option))));
  }

  /// Each command as each runner writes it, Gradle first: the order the docs show them in.
  private static String examples(List<String> commandLines) {
    return commandLines.stream()
        .map(
            commandLine ->
                "{ \"gradle\": %s, \"maven\": %s, \"commandLine\": %s }"
                    .formatted(
                        quoted(Runners.gradle(commandLine)),
                        quoted(Runners.maven(commandLine)),
                        quoted(commandLine)))
        .collect(Collectors.joining(", ", "[", "]"));
  }

  /// The option's example on each command's page, as that command, in each runner; and its setting
  /// in each build file when it has one.
  private static String optionExamples(Option option) {
    return option.commands().stream()
        .sorted(java.util.Comparator.comparing(Main.Subcommand::word))
        .map(
            command -> {
              var example = option.exampleFor(command);
              return "%s: { \"gradle\": %s, \"maven\": %s, \"commandLine\": %s, \"gradleBuild\": %s, \"mavenBuild\": %s }"
                  .formatted(
                      quoted(command.word()),
                      quoted(Runners.gradle(example)),
                      quoted(Runners.maven(example)),
                      quoted(example),
                      quoted(Runners.gradleBuild(option)),
                      quoted(Runners.mavenBuild(option)));
            })
        .collect(Collectors.joining(", ", "{ ", " }"));
  }

  /// How a build plugin sets it: the property on the command line, and the build-file setting when
  /// it has one. Null when the plugins can't set it.
  private static String runner(Option option, String prefix, String setting) {
    if (option.property().isEmpty()) {
      return "null";
    }
    var name = option.property();
    var example = option.negated() ? "false" : option.kind() == Option.Kind.FLAG ? "true" : "…";
    var written =
        setting.startsWith("<")
            ? setting.formatted(name, example, name)
            : setting.formatted(name, example);
    return "{ \"property\": %s, \"setting\": %s }"
        .formatted(quoted(prefix + name), option.buildSetting() ? quoted(written) : "null");
  }

  private static String entries(List<Help.Entry> entries) {
    return entries.stream()
        .map(
            entry ->
                "    { \"name\": %s, \"meaning\": %s, \"detail\": %s }"
                    .formatted(
                        quoted(entry.name()), quoted(entry.meaning()), quoted(entry.detail())))
        .collect(Collectors.joining(",\n"));
  }

  private static String object(List<String> fields) {
    return "    {\n" + String.join(",\n", fields) + "\n    }";
  }

  private static String field(String name, String value) {
    return "      " + quoted(name) + ": " + value;
  }

  private static String strings(List<String> values) {
    return values.stream().map(DocsModel::quoted).collect(Collectors.joining(", ", "[", "]"));
  }

  /// `text` as a JSON string.
  static String quoted(String text) {
    var out = new StringBuilder("\"");
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        default -> out.append(character < 0x20 ? "\\u%04x".formatted((int) character) : character);
      }
    }
    return out.append('"').toString();
  }
}
