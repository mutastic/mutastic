package io.mutastic.api;

import java.util.List;

/// A project's own say over which mutants run: the mutants it ignores by id (from
/// `.mutastic/ignores.yaml`), and from `mutastic.yaml` its rules, then filters of its own (found in
/// its plugins, run only when named), after the built-in ones.
public record Policy(List<Rule> rules, List<Filter> filters, List<Ignore> ignores) {
  /// No rules, no filters and no ignores of the project's own.
  public static final Policy NONE = new Policy(List.of(), List.of(), List.of());

  public Policy {
    rules = List.copyOf(rules);
    filters = List.copyOf(filters);
    ignores = List.copyOf(ignores);
  }

  /// Rules and filters, ignoring nothing.
  public Policy(List<Rule> rules, List<Filter> filters) {
    this(rules, filters, List.of());
  }

  /// The same rules and filters, with these ignores.
  public Policy withIgnores(List<Ignore> found) {
    return new Policy(rules, filters, found);
  }
}
