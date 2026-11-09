package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectTest {
  private static final Path BUILD = Path.of("/work/shop");

  /// `app`'s tests over `core`'s mutants, where `app` also uses `web`, which uses `core`: `web` is
  /// loaded with the mutated classes, so it sees them, and the library stays a library.
  @Test
  void anotherModuleOfTheBuildIsLoadedBesideTheMutatedClasses() {
    var core = module("core", List.of(), List.of(BUILD.resolve("core/build/libs/core.jar")));
    var web = BUILD.resolve("web/build/libs/web.jar");
    var library = Path.of("/home/me/.gradle/caches/junit.jar");
    var app =
        module("app", List.of(BUILD.resolve("core/build/libs/core.jar"), web, library), List.of());

    var running = core.runningTestsOf(app, BUILD);

    assertThat(running.dependencies()).containsExactly(library);
    assertThat(running.alongside()).containsExactly(BUILD.resolve("app/classes"), web);
    assertThat(running.classes()).containsExactly(BUILD.resolve("core/classes"));
  }

  /// As the setup writes them: the build's modules on `app`'s classpath are already `alongside`,
  /// and stay there but for `core`'s jar, which the mutated classes replace.
  @Test
  void theModulesTheSetupPutsAlongsideStayThere() {
    var coreJar = BUILD.resolve("core/build/libs/core.jar");
    var core = module("core", List.of(), List.of(coreJar));
    var web = BUILD.resolve("web/build/libs/web.jar");
    var library = Path.of("/home/me/.gradle/caches/junit.jar");
    var app = module("app", List.of(library), List.of(coreJar, web), List.of());

    var running = core.runningTestsOf(app, BUILD);

    assertThat(running.dependencies()).containsExactly(library);
    assertThat(running.alongside()).containsExactly(BUILD.resolve("app/classes"), web);
  }

  private static Project module(String name, List<Path> dependencies, List<Path> outputs) {
    return module(name, dependencies, List.of(), outputs);
  }

  private static Project module(
      String name, List<Path> dependencies, List<Path> alongside, List<Path> outputs) {
    var dir = BUILD.resolve(name);
    return new Project(
        name,
        Path.of("java"),
        List.of(),
        dir,
        List.of(dir.resolve("classes")),
        List.of(),
        List.of(dir.resolve("test-classes")),
        List.of(),
        List.of(),
        List.of(),
        dependencies,
        alongside,
        outputs,
        TestSelection.ALL,
        Map.of());
  }
}
