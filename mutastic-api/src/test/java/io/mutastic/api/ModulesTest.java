package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModulesTest {
  private static final List<String> MODULES = List.of(":app", ":core");

  @Test
  void aTranspositionIsOneEdit() {
    assertThat(Modules.distance("tset", "test")).isEqualTo(1);
    assertThat(Modules.distance("tset", "set")).isEqualTo(1);
    assertThat(Modules.distance("cor", "core")).isEqualTo(1);
    assertThat(Modules.distance("nope", "core")).isEqualTo(2);
    assertThat(Modules.distance("", "app")).isEqualTo(3);
    assertThat(Modules.distance("APP", "app")).isZero();
  }

  @Test
  void theOneClosestModuleIsOfferedWithinAThirdOfTheName() {
    assertThat(Modules.closest(":cor", MODULES)).hasValue(":core");
    assertThat(Modules.closest(":ocre", MODULES)).hasValue(":core");
    assertThat(Modules.closest(":CORE", MODULES)).hasValue(":core");
    assertThat(Modules.closest(":nope", MODULES)).isEmpty();
    assertThat(Modules.closest(":xyz", MODULES)).isEmpty();
  }

  @Test
  void twoAsCloseAreNeitherOffered() {
    assertThat(Modules.closest(":ab", List.of(":abc", ":abd"))).isEmpty();
    assertThat(Modules.closest(":App", List.of(":app", ":APP"))).isEmpty();
  }

  @Test
  void theCommandsAreCappedButEveryModuleIsNamed() {
    var seven = List.of(":a1", ":a2", ":a3", ":a4", ":a5", ":a6", ":a7");

    var said = Modules.atRoot(Path.of("/b"), seven, "");

    assertThat(said)
        .startsWith("/b is a build's root, with no Java code of its own.\n")
        .contains("Its modules with Java code are :a1, :a2, :a3, :a4, :a5, :a6 and :a7.")
        .contains("  mutastic run :a5")
        .doesNotContain("mutastic run :a6");
  }

  @Test
  void aBuildWithoutModulesSaysWhereItsCodeIs() {
    assertThat(Modules.missing(":nope", List.of(":"), "b"))
        .isEqualTo(
            """
            the build has no module :nope.
            Its Java code is in the build's own directory. To test it:
              mutastic run b""");
    assertThat(Modules.missing(":nope", List.of(), ""))
        .endsWith(
            """
            No module of it has Java code. Run mutastic on the project with your Java code:
              mutastic run <project directory> [:module]""");
    assertThat(Modules.withoutJava(Path.of("/b"), ":docs", MODULES, ""))
        .startsWith("module :docs of the build in /b has no Java code.\n");
  }
}
