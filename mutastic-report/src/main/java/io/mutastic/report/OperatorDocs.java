package io.mutastic.report;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Where docs.mutastic.io describes an operator: its section of its family's page,
/// `https://docs.mutastic.io/java/operators/conditions/#boundary`, in the pattern both editions
/// link by, `/<edition>/operators/<family>/#<id>`. The Java edition's family pages are served at
/// `/concepts/operators/<family>/`, and the site redirects the edition's path there, the anchor
/// kept (`docs/public/_redirects`). Only mutastic's own operators and its shipped rules have one: a
/// plugin's operator, from a jar or a rules file, has no section there, so no link.
///
/// @param base the docs of the mutastic running, `https://docs.mutastic.io/v0.3/`, so an older
///   install links to what it does
public record OperatorDocs(String base) {
  /// The latest release's docs, for a build from source.
  public static final OperatorDocs LATEST = new OperatorDocs("https://docs.mutastic.io/");

  /// The family pages, under the docs' base: the edition's own path.
  private static final String PAGES = "java/operators/";

  /// Each family's page, by the anchors of the operators it describes.
  private static final Map<String, List<String>> FAMILIES =
      Map.of(
          "arguments",
          List.of("omit-call", "swap-arguments", "null-parameter", "drop-vararg", "omit-argument"),
          "arithmetic",
          List.of(
              "math",
              "arithmetic-replacement",
              "other-arithmetic",
              "bitwise",
              "keep-operand",
              "constant-value",
              "flip-increment",
              "remove-negation",
              "unchecked-arithmetic"),
          "calls",
          List.of("remove-void-call", "remove-discarded-call", "remove-assignment", "remove-field-write"),
          "conditions",
          List.of(
              "boundary",
              "comparison",
              "other-comparison",
              "remove-conditional",
              "logical-connector",
              "predicate-connector-swap",
              "predicate-negation-removal"),
          "control",
          List.of("remove-switch-arm", "drop-catch-type"),
          "library",
          List.of(
              "string-method-swap", "indexof-swap", "math-method-swap", "string-constant", "regex-constant"),
          "optional",
          List.of(
              "optional-presence-swap",
              "optional-made-empty",
              "optional-filter-removal",
              "optional-default"),
          "returns",
          List.of("return-value", "replace-body", "remove-throw"),
          "streams",
          List.of("stream-stage-removal", "stream-match-swap", "other-stage-removal"));

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

  /// `https://docs.mutastic.io/java/operators/conditions/#boundary`; empty for a plugin's
  /// operator.
  public Optional<String> url(Operator operator) {
    return anchor(operator)
        .flatMap(
            anchor -> family(anchor).map(family -> base + PAGES + family + "/#" + anchor));
  }

  /// `conditions`: the family whose page describes the operator with that anchor.
  static Optional<String> family(String anchor) {
    return FAMILIES.entrySet().stream()
        .filter(family -> family.getValue().contains(anchor))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  /// `boundary`: the id of the operator's section on its family's page, and of its row on the
  /// operators page.
  static Optional<String> anchor(Operator operator) {
    var id = operator.id();
    if (operator instanceof BuiltIn || NAMED_FOR_THEIR_ID.contains(id)) {
      return Optional.of(id.substring(id.indexOf('/') + 1));
    }
    return Optional.ofNullable(NAMED_OTHERWISE.get(id));
  }
}
