package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/// The build tools mutastic ships with ([BuildTool]s, found with [ServiceLoader]), and which one a
/// directory is a build of.
final class BuildTools {
  private BuildTools() {}

  /// The tool whose build `directory` is: the first that recognises it.
  static BuildTool of(Path directory) {
    if (!Files.isDirectory(directory)) {
      throw new SetupFailed("no-directory", "there's no directory %s".formatted(directory));
    }
    return find(directory).orElseThrow(() -> new SetupFailed("no-project", notABuild(directory)));
  }

  /// The tool whose build `directory` is, if any recognises it.
  static Optional<BuildTool> find(Path directory) {
    return tools().stream().filter(tool -> tool.recognises(directory)).findFirst();
  }

  private static List<BuildTool> tools() {
    return ServiceLoader.load(BuildTool.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(BuildTool::name))
        .toList();
  }

  /// `directory` isn't a build: the one it's inside, if it's in one, else what makes a build.
  private static String notABuild(Path directory) {
    var tools = tools();
    return enclosing(directory, tools)
        .map(found -> inside(directory, found))
        .orElseGet(() -> nowhere(directory, tools));
  }

  /// A build a directory is inside, and the tool whose build it is.
  private record Enclosing(BuildTool tool, Path build) {}

  /// The nearest build above `directory`.
  private static Optional<Enclosing> enclosing(Path directory, List<BuildTool> tools) {
    for (var above = directory.getParent(); above != null; above = above.getParent()) {
      var build = above;
      var tool = tools.stream().filter(each -> each.recognises(build)).findFirst();
      if (tool.isPresent()) {
        return Optional.of(new Enclosing(tool.get(), build));
      }
    }
    return Optional.empty();
  }

  /// `directory` is inside a build: the command that tests the build, or its module.
  private static String inside(Path directory, Enclosing found) {
    var address = found.tool().address(found.build());
    var where =
        address.module().equals(":")
            ? "the %s build in %s".formatted(found.tool().name(), address.directory())
            : "module %s of the %s build in %s"
                .formatted(address.module(), found.tool().name(), address.directory());
    var command = new ArrayList<>(List.of("mutastic", "run"));
    command.addAll(typed(Path.of("").toAbsolutePath().normalize(), address));
    return """
        %s isn't a build's top directory.
        It's inside %s. To test it:
          %s"""
        .formatted(directory, where, String.join(" ", command));
  }

  /// The build's directory and module as typed from `here`: relative to it when one is inside the
  /// other, as `..`, and nothing for `here` itself.
  private static List<String> typed(Path here, BuildTool.Address address) {
    var words = new ArrayList<String>();
    var build = address.directory();
    if (!build.equals(here)) {
      words.add(
          here.startsWith(build) || build.startsWith(here)
              ? Main.slashed(here.relativize(build))
              : build.toString());
    }
    if (!address.module().equals(":")) {
      words.add(address.module());
    }
    return words;
  }

  private static String nowhere(Path directory, List<BuildTool> tools) {
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
