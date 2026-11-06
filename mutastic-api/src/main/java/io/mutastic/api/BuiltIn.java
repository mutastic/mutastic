package io.mutastic.api;

import java.util.Locale;

/// mutastic's own operators. How each finds its sites and rewrites them is the engine's.
public enum BuiltIn implements Operator {
  BOUNDARY(
      "`<` becomes `<=` and the other way round: the off-by-one at the edge of a range, which tests"
          + " that only check values far from the edge never notice."),

  /// In `stronger`: with [#BOUNDARY]'s edge moved and [#REMOVE_CONDITIONAL]'s condition always true
  /// or false, the smallest set of changes whose catches catch every other change of a comparison.
  /// For `a < b` that's `a <= b` (boundary), `a != b` (this one) and `false`. Measured on
  /// mutastic's own code (6 Nov 2026), most of its misses were noise: counters that count up to
  /// their end, sizes compared with a value they never pass.
  COMPARISON(
      "A comparison becomes the one that differs from it on one side of its edge: `<` becomes"
          + " `!=`, `<=` becomes `==`, `==` becomes `<=` and `>=`, `!=` becomes `<` and `>`, and"
          + " the same for `>` and `>=`. It goes unnoticed when no test checks the side of the"
          + " comparison where the two disagree.",
      Tier.STRONGER),

  /// In `stronger`: the rest of what a comparison can become, and a condition the other way round,
  /// as `mutastic/negate-conditional` was, which it replaced (6 Nov 2026). A test that catches the
  /// mutants of [#BOUNDARY], [#COMPARISON] and [#REMOVE_CONDITIONAL] catches these too, so in the
  /// defaults their catches would only add to the score.
  OTHER_COMPARISON(
      "A comparison becomes one of the others: `<` becomes `>`, `>=` or `==`, and any condition"
          + " can go the other way, `== null` to `!= null`. It goes unnoticed when no test checks"
          + " what happens on one side of an `if`.",
      Tier.STRONGER),

  MATH("`+` becomes `-`, `*` becomes `/`, and back, on `int` and `long`."),

  /// Replaced `mutastic/return-default` (6 Nov 2026), which returned each type's default: a `return
  /// false` already returned it, and `null` is rarely what a method returning a list gets wrong.
  RETURN_VALUE(
      "A method returns the value its kind of method most plausibly gets wrong: a `boolean` the"
          + " other one, a number `0` (`1` where it returns `0`), an `Optional`, string,"
          + " collection or stream an empty one, anything else `null`. It goes unnoticed when"
          + " tests call the method but never look at what it returns."),

  REMOVE_VOID_CALL(
      "A call that returns nothing is removed. It goes unnoticed when no test checks what the call"
          + " does."),

  /// A call whose result is thrown away, removed: `list.add(item);`, `map.put(key, value);`,
  /// `text.append(more);`. Its result isn't used, so only what it does matters, as with a call that
  /// returns nothing. A fluent call that returns its receiver is no different: removing
  /// `builder.append(x);` loses the append.
  REMOVE_DISCARDED_CALL(
      "A call whose result is thrown away is removed: `items.add(item);`, `seen.put(key, value);`."
          + " It goes unnoticed when no test checks what the call does."),

  /// An assignment to a local variable that already has a value, removed: it keeps that value. A
  /// declaration's first value isn't changed, as the code wouldn't load without it.
  REMOVE_ASSIGNMENT(
      "An assignment to a variable that already has a value is removed, so it keeps that value. It"
          + " goes unnoticed when no test checks anything that depends on the new one."),

  /// A write to a field, removed: it keeps the value it had. Constructors and static initialisers
  /// are left alone, as for every operator, and a plain setter is arid, so what's left is state a
  /// method changes.
  REMOVE_FIELD_WRITE(
      "A write to a field is removed, so it keeps the value it had. It goes unnoticed when no test"
          + " checks the field afterwards, directly or through what reads it.",
      Tier.STRONGER),

  REMOVE_CONDITIONAL(
      "A condition stops deciding: the code it guards always runs, or never does. It goes"
          + " unnoticed when no test needs the condition to be true one time and false another."),

  /// `&&` to `||` and back. javac has no opcode for either, so the engine finds the two jumps javac
  /// writes for a condition of two tests joined by one, and turns the first round.
  LOGICAL_CONNECTOR(
      "`&&` becomes `||`, and `||` becomes `&&`, in a condition of two tests. It goes unnoticed"
          + " when no test has one of them true and the other false."),

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
    return this == REMOVE_VOID_CALL || this == REMOVE_DISCARDED_CALL;
  }

  @Override
  public Tier tier() {
    return tier;
  }

  /// One of the operators whose mutants' ids were fixed before operators were open: their ids leave
  /// the operator out, and stay as they were. Every other operator's put it in, including
  /// [#OTHER_COMPARISON], which replaced the negated condition: its ids changed with it.
  public boolean first() {
    return switch (this) {
      case BOUNDARY, MATH, REMOVE_VOID_CALL -> true;
      default -> false;
    };
  }
}
