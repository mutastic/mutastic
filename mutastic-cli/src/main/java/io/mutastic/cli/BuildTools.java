package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/// The build tools mutastic ships with ([BuildTool]s, found with [ServiceLoader]), and which one a
/// directory is a build of.
final class BuildTools {
  private BuildTools() {}

  /// The tool whose build `directory` is: the first that recognises it.
  static BuildTool of(Path directory) {
    if (!Files.isDirectory(directory)) {
      throw new SetupFailed("there's no directory %s".formatted(directory));
    }
    var tools =
        ServiceLoader.load(BuildTool.class).stream()
            .map(ServiceLoader.Provider::get)
            .sorted(Comparator.comparing(BuildTool::name))
            .toList();
    return tools.stream()
        .filter(tool -> tool.recognises(directory))
        .findFirst()
        .orElseThrow(() -> new SetupFailed(notABuild(directory, tools)));
  }

  private static String notABuild(Path directory, List<BuildTool> tools) {
    var kinds = tools.stream().map(BuildTool::name).collect(Collectors.joining(" or "));
    var files = tools.stream().map(BuildTool::buildFiles).collect(Collectors.joining(" or "));
    return """
        %s isn't a %s build: it has no %s.
        Run mutastic on a %s project, naming it if it's not where you are:
          mutastic run <project directory> [:module]
          mutastic run --only=<id> <project directory> [:module]
        %s"""
        .formatted(directory, kinds, files, kinds, SetupFailed.ID_HINT);
  }
}
