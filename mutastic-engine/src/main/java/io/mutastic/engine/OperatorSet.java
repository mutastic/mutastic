package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutator;
import io.mutastic.api.Operator;
import io.mutastic.api.Operator.Tier;
import io.mutastic.api.Policy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/// Which operators a run uses: every operator there is (mutastic's own, its stream rules, and what
/// the project's plugins and rules files add), narrowed to what `--operators` or `operators:`
/// chooses.
///
/// A choice is a list of words, each one of:
///
/// - a set: `defaults` (what runs unless told otherwise), or `stronger` (those and the stronger
///   ones: every operator);
/// - an operator's id, `mutastic/arithmetic-replacement`;
/// - a namespace, every operator whose id starts with it, `stream`;
///
/// with a `-` in front to leave it out: `defaults,-stream`. A list that only leaves things out
/// starts from `defaults`.
public final class OperatorSet {
  private static final List<String> SETS = List.of("defaults", "stronger");

  private OperatorSet() {}

  /// Every operator there is for this project, in a fixed order: mutastic's own, then its rules,
  /// then the project's.
  public static List<Operator> available(List<Mutator> added) {
    var all = new ArrayList<Operator>(Arrays.asList(BuiltIn.values()));
    all.addAll(CallRules.BUILT_IN);
    all.addAll(added);
    return List.copyOf(all);
  }

  /// The operators the policy chooses.
  ///
  /// @throws IllegalArgumentException for a word that names nothing, saying what there is
  public static List<Operator> active(Policy policy) {
    var all = available(policy.mutators());
    var words = policy.operators().isEmpty() ? List.of("defaults") : policy.operators();
    var chosen = new LinkedHashSet<Operator>();
    if (words.stream().allMatch(word -> word.startsWith("-"))) {
      chosen.addAll(named("defaults", all));
    }
    for (String word : words) {
      boolean without = word.startsWith("-");
      var name = without ? word.substring(1) : word;
      var named = named(name, all);
      if (named.isEmpty()) {
        throw new IllegalArgumentException(
            "no operator or set is called %s; there are %s"
                .formatted(name, String.join(", ", known(all))));
      }
      if (without) {
        named.forEach(chosen::remove);
      } else {
        chosen.addAll(named);
      }
    }
    return all.stream().filter(chosen::contains).toList();
  }

  private static List<Operator> named(String name, List<Operator> all) {
    return switch (name) {
      case "defaults" -> all.stream().filter(op -> op.tier() == Tier.DEFAULTS).toList();
      case "stronger" -> all;
      default ->
          all.stream()
              .filter(op -> op.id().equals(name) || op.id().startsWith(name + "/"))
              .toList();
    };
  }

  /// The sets, the namespaces and every operator's id.
  private static Set<String> known(List<Operator> all) {
    var names = new LinkedHashSet<>(SETS);
    var rest = new TreeSet<String>();
    for (Operator op : all) {
      rest.add(op.id().substring(0, op.id().indexOf('/')));
      rest.add(op.id());
    }
    names.addAll(rest);
    return names;
  }
}
