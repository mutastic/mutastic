package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import io.mutastic.api.RealPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

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

  /// The build, and the module, a directory that isn't a build's own is inside: empty for a build's
  /// directory, one that isn't there, and one in no build.
  static Optional<BuildTool.Address> enclosing(Path directory) {
    if (!Files.isDirectory(directory) || find(directory).isPresent()) {
      return Optional.empty();
    }
    return enclosing(directory, tools()).map(found -> found.tool().address(found.build()));
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
    var build = RealPath.of(address.directory());
    var from = RealPath.of(here);
    if (!build.equals(from)) {
      words.add(
          from.startsWith(build) || build.startsWith(from)
              ? Main.slashed(from.relativize(build))
              : address.directory().toString());
    }
    if (!address.module().equals(":")) {
      words.add(address.module());
    }
    return words;
  }

  /// No build in `directory` or above it: where mutastic looked, and for what. The command to run
  /// on the project instead follows, from the command line ([Failure]).
  private static String nowhere(Path directory, List<BuildTool> tools) {
    var files =
        tools.stream()
            .flatMap(tool -> java.util.Arrays.stream(tool.buildFiles().split(" or ", -1)))
            .toList();
    var place = directory.toString().equals(".") ? "here" : "in " + directory;
    return """
        there's no project %s for mutastic to test.
        It looked in %s and every directory above it, for %s.
        Go to your project, or name its directory:"""
        .formatted(place, directory.toAbsolutePath().normalize(), Options.listed(files, "or"));
  }
}
