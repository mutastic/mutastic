package io.mutastic.api;

import java.util.Locale;

/// mutastic's own operators. How each finds its sites and rewrites them is the engine's.
public enum BuiltIn implements Operator {
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
          + " does."),

  REMOVE_CONDITIONAL(
      "A condition stops deciding: the code it guards always runs, or never does. It goes"
          + " unnoticed when no test needs the condition to be true one time and false another."),

  ARITHMETIC_REPLACEMENT(
      "`+` becomes `*`, on `int` and `long`. It goes unnoticed when tests only add numbers whose"
          + " sum and product agree, `2 + 2` say.",
      Tier.STRONGER);

  private final String explanation;
  private final Tier tier;

  BuiltIn(String explanation) {
    this(explanation, Tier.DEFAULTS);
  }

  BuiltIn(String explanation, Tier tier) {
    this.explanation = explanation;
    this.tier = tier;
  }

  @Override
  public String id() {
    return "mutastic/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @Override
  public String explanation() {
    return explanation;
  }

  @Override
  public boolean onCalls() {
    return this == REMOVE_VOID_CALL;
  }

  @Override
  public Tier tier() {
    return tier;
  }

  /// One of the first five, whose mutants' ids were fixed before operators were open: their ids
  /// leave the operator out, and stay as they were. Every other operator's put it in.
  public boolean first() {
    return switch (this) {
      case BOUNDARY, NEGATE_CONDITIONAL, MATH, RETURN_DEFAULT, REMOVE_VOID_CALL -> true;
      default -> false;
    };
  }
}
