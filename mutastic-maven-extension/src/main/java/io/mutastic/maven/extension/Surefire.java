package io.mutastic.maven.extension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/// Surefire's settings for one module, evaluated as Maven evaluates them for the plugin: `${…}`
/// from properties and the project. A module without Surefire has none of them.
final class Surefire {
  private final @Nullable MojoExecution execution;
  private final @Nullable PluginParameterExpressionEvaluator evaluator;

  Surefire(MavenSession session, @Nullable MojoExecution execution) {
    this.execution = execution;
    this.evaluator =
        execution == null ? null : new PluginParameterExpressionEvaluator(session, execution);
  }

  /// A setting's value, or its default when it has no value of its own; null when it has neither.
  @Nullable String value(String name) throws Exception {
    var setting = setting(name);
    if (setting == null) {
      return null;
    }
    var raw =
        setting.getValue() != null ? setting.getValue() : setting.getAttribute("default-value");
    return raw == null ? null : evaluated(raw);
  }

  /// A list setting's entries: `<includes><include>…</include></includes>`.
  List<String> list(String name) throws Exception {
    var values = new ArrayList<String>();
    var setting = setting(name);
    if (setting != null) {
      for (Xpp3Dom entry : setting.getChildren()) {
        var value = entry.getValue() == null ? null : evaluated(entry.getValue());
        if (value != null && !value.isBlank()) {
          values.add(value.strip());
        }
      }
    }
    return values;
  }

  /// A map setting's entries:
  /// `<systemPropertyVariables><name>value</name></systemPropertyVariables>`.
  Map<String, String> map(String name) throws Exception {
    var values = new LinkedHashMap<String, String>();
    var setting = setting(name);
    if (setting != null) {
      for (Xpp3Dom entry : setting.getChildren()) {
        var value = entry.getValue() == null ? null : evaluated(entry.getValue());
        values.put(entry.getName(), value == null ? "" : value);
      }
    }
    return values;
  }

  private @Nullable Xpp3Dom setting(String name) {
    var current = execution;
    return current == null || current.getConfiguration() == null
        ? null
        : current.getConfiguration().getChild(name);
  }

  private @Nullable String evaluated(String raw) throws Exception {
    var current = evaluator;
    var value = current == null ? null : current.evaluate(raw);
    return value == null ? null : value.toString();
  }
}
