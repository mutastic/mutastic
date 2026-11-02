package io.mutastic.maven.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/// The command line over the POM, for one run; the POM over the plugin's defaults is Maven's own,
/// and `MavenPluginTest` runs all three.
class SettingsTest {
  @Test
  void theCommandLineWinsAndItsListReplacesThePoms() {
    var configured = Map.of("threshold", "90", "testsFrom", ":app,:web", "suggestions", "true");

    var settings =
        Settings.of(configured, Map.of("mutastic.threshold", "0", "mutastic.testsFrom", ":api"));

    assertThat(settings)
        .containsEntry("threshold", "0")
        .containsEntry("testsFrom", ":api")
        .containsEntry("suggestions", "true");
  }

  /// Anything the POM can't set, `mutastic.only` say, is passed on as it is.
  @Test
  void whatThePomDoesntSetIsPassedOn() {
    assertThat(Settings.of(Map.of(), Map.of("mutastic.only", "m855c8e3c")))
        .containsExactly(Map.entry("only", "m855c8e3c"));
  }
}
