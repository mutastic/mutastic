package io.mutastic.api;

import java.util.List;

/// A project's own say over which mutants run: the mutants it ignores by id (from
/// `.mutastic/ignores.yaml`), and from `mutastic.yaml` its rules, then filters of its own (found in
/// its plugins, run only when named), after the built-in ones; which operators run, and the
/// operators its plugins and rules files add.
///
/// @param operators which operators run, as `--operators` or `operators:` says: sets (`defaults`,
///   `stronger`, `extended`, `all`), operator ids (`mutastic/arithmetic-replacement`) and their
///   namespaces (`stream`), any with a `-` in front to leave it out; none means `defaults`
/// @param mutators the operators the project's plugins and rules files add
/// @param classMutators the operators the project's plugins add that change a class as a whole
public record Policy(
    List<Rule> rules,
    List<Filter> filters,
    List<Ignore> ignores,
    List<String> operators,
    List<Mutator> mutators,
    List<ClassMutator> classMutators) {
  /// No rules, no filters and no ignores of the project's own, and the default operators.
  public static final Policy NONE = new Policy(List.of(), List.of(), List.of());

  public Policy {
    rules = List.copyOf(rules);
    filters = List.copyOf(filters);
    ignores = List.copyOf(ignores);
    operators = List.copyOf(operators);
    mutators = List.copyOf(mutators);
    classMutators = List.copyOf(classMutators);
  }

  /// With no operators that change a class as a whole.
  public Policy(
      List<Rule> rules,
      List<Filter> filters,
      List<Ignore> ignores,
      List<String> operators,
      List<Mutator> mutators) {
    this(rules, filters, ignores, operators, mutators, List.of());
  }

  /// Rules, filters and ignores, with the default operators.
  public Policy(List<Rule> rules, List<Filter> filters, List<Ignore> ignores) {
    this(rules, filters, ignores, List.of(), List.of(), List.of());
  }

  /// Rules and filters, ignoring nothing.
  public Policy(List<Rule> rules, List<Filter> filters) {
    this(rules, filters, List.of());
  }

  /// The same, with these ignores.
  public Policy withIgnores(List<Ignore> found) {
    return new Policy(rules, filters, found, operators, mutators, classMutators);
  }

  /// The same, with these operators chosen and these added.
  public Policy withOperators(List<String> chosen, List<Mutator> added) {
    return new Policy(rules, filters, ignores, chosen, added, classMutators);
  }

  /// The same, with these added, which change a class as a whole.
  public Policy withClassMutators(List<ClassMutator> added) {
    return new Policy(rules, filters, ignores, operators, mutators, added);
  }
}
