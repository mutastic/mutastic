package io.mutastic.engine;

import io.mutastic.api.AridRule;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// What the project brings back in some code that mutastic would skip as arid: every arid skip, as
/// a `mutate:` rule or `@DoMutate` with no `arid:` list does, or only the rules listed.
///
/// @param all every arid skip, a plugin's too
/// @param rules the ids of the rules brought back, when not all are
record KeptArid(boolean all, Set<String> rules) {
  /// Nothing brought back.
  static final KeptArid NONE = new KeptArid(false, Set.of());

  KeptArid {
    rules = Set.copyOf(rules);
  }

  /// What one `mutate:` rule or `@DoMutate` brings back: all, or the rules it lists.
  static KeptArid of(List<String> listed) {
    return listed.isEmpty()
        ? new KeptArid(true, Set.of())
        : new KeptArid(false, Set.copyOf(listed));
  }

  /// What this and `other` bring back between them.
  KeptArid and(KeptArid other) {
    if (all || other.all) {
      return new KeptArid(true, Set.of());
    }
    var both = new HashSet<>(rules);
    both.addAll(other.rules);
    return new KeptArid(false, both);
  }

  /// Whether a mutant skipped for `reason` (`arid: only waits`) comes back.
  boolean keeps(String reason) {
    return reason.startsWith("arid:")
        && (all || AridRule.of(reason).filter(rule -> rules.contains(rule.id())).isPresent());
  }

  /// Whether `rule`'s skips come back.
  boolean keeps(AridRule rule) {
    return all || rules.contains(rule.id());
  }
}
