package io.mutastic.maven.plugin;

import java.util.Map;
import java.util.TreeMap;

/// What a run uses: what the POM sets, or the plugin's defaults, with the command line's
/// `mutastic.` properties over it.
final class Settings {
  private Settings() {}

  /// `configured` by name (`threshold` → `80`), and `commandLine` by property (`mutastic.threshold`
  /// → `60`): the command line's wins, and a list given there replaces the configured one whole.
  static Map<String, String> of(Map<String, String> configured, Map<String, String> commandLine) {
    var settings = new TreeMap<>(configured);
    commandLine.forEach((name, value) -> settings.put(name.substring("mutastic.".length()), value));
    return settings;
  }
}
