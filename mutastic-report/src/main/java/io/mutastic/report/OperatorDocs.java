package io.mutastic.report;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Where docs.mutastic.io describes an operator: its row on the operators page,
/// `https://docs.mutastic.io/concepts/operators/#boundary`, which links on to its family's page.
/// Only mutastic's own operators and its shipped rules have one: a plugin's operator, from a jar or
/// a rules file, has no row there, so no link.
///
/// @param base the docs of the mutastic running, `https://docs.mutastic.io/v0.3/`, so an older
///   install links to what it does
public record OperatorDocs(String base) {
  /// The latest release's docs, for a build from source.
  public static final OperatorDocs LATEST = new OperatorDocs("https://docs.mutastic.io/");

  /// The operators page, under the docs' base.
  private static final String PAGE = "concepts/operators/";

  /// The shipped rules whose row is named as mutastic's own operators' are: the id's name, after
  /// its namespace.
  private static final Set<String> NAMED_FOR_THEIR_ID =
      Set.of("library/omit-argument", "library/unchecked-arithmetic");

  /// The rows named otherwise: for what the operator changes, as they were before the ids were
  /// settled, and kept so that links to them hold. `predicate/remove-not` and `optional/no-default`
  /// share a row with the operator they pair with.
  private static final Map<String, String> NAMED_OTHERWISE =
      Map.ofEntries(
          Map.entry("predicate/swap-connector", "predicate-connector-swap"),
          Map.entry("predicate/remove-negate", "predicate-negation-removal"),
          Map.entry("predicate/remove-not", "predicate-negation-removal"),
          Map.entry("library/swap-string", "string-method-swap"),
          Map.entry("library/swap-index-of", "indexof-swap"),
          Map.entry("library/swap-math", "math-method-swap"),
          Map.entry("stream/remove-stage", "stream-stage-removal"),
          Map.entry("stream/swap-match", "stream-match-swap"),
          Map.entry("stream/remove-other-stage", "other-stage-removal"),
          Map.entry("optional/swap-present", "optional-presence-swap"),
          Map.entry("optional/empty", "optional-made-empty"),
          Map.entry("optional/remove-filter", "optional-filter-removal"),
          Map.entry("optional/always-default", "optional-default"),
          Map.entry("optional/no-default", "optional-default"));

  /// `https://docs.mutastic.io/concepts/operators/#boundary`; empty for a plugin's operator.
  public Optional<String> url(Operator operator) {
    return anchor(operator).map(anchor -> base + PAGE + "#" + anchor);
  }

  /// `boundary`: the id of the operator's row on the operators page.
  static Optional<String> anchor(Operator operator) {
    var id = operator.id();
    if (operator instanceof BuiltIn || NAMED_FOR_THEIR_ID.contains(id)) {
      return Optional.of(id.substring(id.indexOf('/') + 1));
    }
    return Optional.ofNullable(NAMED_OTHERWISE.get(id));
  }
}
