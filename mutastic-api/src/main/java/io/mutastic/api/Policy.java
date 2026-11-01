package io.mutastic.api;

import java.util.List;

/// A project's own say over which mutants run, from `mutastic.yaml`: its rules, then filters of its
/// own (found in its plugins, run only when named), after the built-in ones.
public record Policy(List<Rule> rules, List<Filter> filters) {
  /// No rules and no filters of the project's own.
  public static final Policy NONE = new Policy(List.of(), List.of());

  public Policy {
    rules = List.copyOf(rules);
    filters = List.copyOf(filters);
  }
}
