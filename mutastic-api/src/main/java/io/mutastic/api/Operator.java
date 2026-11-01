package io.mutastic.api;

import java.util.Locale;

/// The mutation operators: what kinds of change mutastic makes. How each finds its sites and
/// rewrites them is the engine's.
///
/// Operators work on two kinds of site, counted apart: instructions (arithmetic, branches, returns)
/// and calls. A new operator on calls never shifts the ids of instruction mutants.
public enum Operator {
  BOUNDARY(
      "`<` becomes `<=` and the other way round: the off-by-one at the edge of a range, which tests"
          + " that only check values far from the edge never notice."),

  NEGATE_CONDITIONAL(
      "A condition goes the other way: `==` becomes `!=`, `<` becomes `>=`. It goes unnoticed when"
          + " no test checks what happens on one side of an `if`."),

  MATH("`+` becomes `-`, `*` becomes `/`, and back, on `int` and `long`."),

  RETURN_DEFAULT(
      "A method returns its type's default (`0`, `false`, `null`) instead of what it computed. It"
          + " goes unnoticed when tests call the method but never look at what it returns."),

  REMOVE_VOID_CALL(
      "A call that returns nothing is removed. It goes unnoticed when no test checks what the call"
          + " does.");

  private final String explanation;

  Operator(String explanation) {
    this.explanation = explanation;
  }

  /// What it changes, and when a test misses that, in plain words: for `mutastic explain`.
  public String explanation() {
    return explanation;
  }

  /// `mutastic/boundary`: how reports and rules name it.
  public String id() {
    return "mutastic/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /// Whether it works on call sites rather than instruction sites.
  public boolean onCalls() {
    return this == REMOVE_VOID_CALL;
  }
}
