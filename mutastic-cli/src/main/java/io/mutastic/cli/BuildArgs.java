package io.mutastic.cli;

import io.mutastic.api.BuildArguments;
import io.mutastic.api.BuildTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/// The arguments a run's build gets: those after `--`, else `MUTASTIC_BUILD_ARGS`, else
/// `mutastic.yaml`'s `build:` `args:`. The first given is used whole, in place of the others.
final class BuildArgs {
  private BuildArgs() {}

  static final String VARIABLE = "MUTASTIC_BUILD_ARGS";

  /// What starts the build tool's arguments on the command line.
  static final String START = "--";

  /// `args` without the build tool's: mutastic's own, up to the first `--`.
  static List<String> own(List<String> args) {
    int start = args.indexOf(START);
    return start < 0 ? args : args.subList(0, start);
  }

  /// What was typed after the first `--`: empty when there's none.
  static Optional<List<String>> typed(List<String> args) {
    int start = args.indexOf(START);
    return start < 0
        ? Optional.empty()
        : Optional.of(List.copyOf(args.subList(start + 1, args.size())));
  }

  /// `tool`, giving its build the run's arguments.
  ///
  /// @throws Main.UsageException, or [Main.ConfigException], when one fights how mutastic drives
  ///   the build
  static BuildTool on(
      BuildTool tool, Main.Command command, Path directory, Map<String, String> env) {
    return tool.withArguments(of(tool, command, directory, env));
  }

  static BuildArguments of(
      BuildTool tool, Main.Command command, Path directory, Map<String, String> env) {
    var typed = command.buildArguments();
    if (typed.isPresent()) {
      return checked(
          BuildArguments.typed(typed.get()),
          tool,
          command,
          refused -> new Main.UsageException("option-value", said(refused, tool)));
    }
    var variable = env.getOrDefault(VARIABLE, "");
    if (!variable.isBlank()) {
      return checked(
          BuildArguments.quiet(split(variable)),
          tool,
          command,
          refused ->
              new Main.ConfigException("environment", VARIABLE + ": " + said(refused, tool)));
    }
    var configuration =
        Configuration.read(Configuration.nearest(module(directory, command.module()), directory));
    var configured = configuration.buildArguments();
    return configured.isEmpty()
        ? BuildArguments.NONE
        : checked(
            BuildArguments.quiet(configured.get()),
            tool,
            command,
            refused ->
                new Main.ConfigException(
                    place(configuration, refused) + ": " + said(refused, tool)));
  }

  /// Where the file lists what's refused: its line, when the item starts with it.
  private static String place(Configuration configuration, BuildArguments.Refused refused) {
    var first = refused.name().split(" ", 2)[0];
    return configuration.buildArguments().orElse(List.of()).stream()
        .filter(item -> item.startsWith(first))
        .findFirst()
        .map(item -> configuration.place(Configuration.BUILD_ARGS, item))
        .orElse(Configuration.FILE);
  }

  private static List<String> split(String variable) {
    try {
      return BuildArguments.split(variable);
    } catch (IllegalArgumentException e) {
      throw new Main.ConfigException("environment", VARIABLE + ": " + e.getMessage());
    }
  }

  private static BuildArguments checked(
      BuildArguments arguments,
      BuildTool tool,
      Main.Command command,
      Function<BuildArguments.Refused, RuntimeException> failure) {
    var refused = tool.refused(arguments.words(), command.suite());
    if (refused.isPresent()) {
      throw failure.apply(refused.get());
    }
    return arguments;
  }

  private static String said(BuildArguments.Refused refused, BuildTool tool) {
    return "%s can't be passed to %s: %s".formatted(refused.name(), tool.name(), refused.reason());
  }

  /// The module's directory as its path names it, where its `mutastic.yaml` is looked for: the
  /// build isn't read yet, so a Gradle module kept in a directory of another name uses the run's.
  private static Path module(Path directory, String module) {
    if (module.equals(":")) {
      return directory;
    }
    var named = directory.resolve(module.substring(1).replace(':', '/'));
    return Files.isDirectory(named) ? named : directory;
  }
}
