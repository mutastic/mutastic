package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Where a mutant's line is, for an ignore's check and a `--since` scope.
class SourcePathsTest {
  @TempDir Path root;

  private static Mutant mutant() {
    return new Mutant(
        "probe/ProbeKt", "uses", "(I)I", 0, 4, BuiltIn.MATH, "IADD", "ISUB", Optional.empty());
  }

  @Test
  void codeCopiedInFromAnotherFileIsInThatFile() throws Exception {
    var helpers = root.resolve("probe/Helpers.kt");
    Files.createDirectories(helpers.getParent());
    Files.writeString(helpers, "x");

    var found =
        SourcePaths.of(
            mutant().at(4, Optional.of("probe/Helpers.kt")), Optional.empty(), List.of(root));

    assertThat(found).contains(helpers.toAbsolutePath().normalize());
  }

  @Test
  void codeCopiedInFromAFileNotHereIsInNoFile() {
    var found =
        SourcePaths.of(
            mutant().at(4, Optional.of("kotlin/_Collections.kt")), Optional.empty(), List.of(root));

    assertThat(found).isEmpty();
  }

  @Test
  void aMutantKeepsItsPlaceWhenSkippedOrRunAnyway() {
    var moved = mutant().at(9, Optional.of("probe/Helpers.kt"));

    assertThat(moved.skippedFor("user: no").inlinedFrom()).contains("probe/Helpers.kt");
    assertThat(moved.unskipped().inlinedFrom()).contains("probe/Helpers.kt");
    assertThat(moved.line()).isEqualTo(9);
    assertThat(moved.id()).as("the line and file aren't in the id").isEqualTo(mutant().id());
  }
}
