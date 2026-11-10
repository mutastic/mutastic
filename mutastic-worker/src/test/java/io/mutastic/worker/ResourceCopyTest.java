package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The engine's copy of the project's resources is left out of a lookup of every resource of a
/// name, where the worker's loaders have found each already.
class ResourceCopyTest {
  @TempDir Path temp;

  @AfterEach
  void none() {
    ResourceCopy.at(null);
  }

  @Test
  void theCopysAreLeftOut() throws Exception {
    var copy = temp.resolve("resources-worker");
    var own = temp.resolve("classes/db/migration/V1.sql").toUri().toURL();
    var copied = copy.resolve("db/migration/V1.sql").toUri().toURL();
    var library = temp.resolve("lib.jar").toUri().toURL();

    assertThat(ResourceCopy.without(List.of(own, copied, library))).hasSize(3);
    ResourceCopy.at(copy);
    assertThat(ResourceCopy.without(List.of(own, copied, library))).containsExactly(own, library);
  }
}
