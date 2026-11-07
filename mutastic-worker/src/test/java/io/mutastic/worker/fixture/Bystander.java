package io.mutastic.worker.fixture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/// Run before `ClassSetup` by `TestScopesTest`, reaching nothing: what runs after it isn't its
/// doing.
@Tag("fixture")
public class Bystander {
  @Test
  void reachesNothing() {}
}
