package io.mutastic.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// The editions' contract (docs/public/contract/v1.json), as an edition's checks read it: what was
/// found to differ from it, and whether its `differences` account for each. A difference found that
/// no entry names fails; so does an entry whose items name nothing found, since the edition now
/// matches and the entry should go. ContractTest finds; this judges.
///
/// The Rust edition's tests/contract.rs judges the same way, so the two read one file alike.
final class EditionContract {
  static final String EDITION = "java";

  /// The contract, published beside the schemas: its JSON Schema is schemas/contract/v1.json.
  static final Path FILE =
      Path.of(System.getProperty("mutastic.schemas")).resolveSibling("contract/v1.json");

  private final JsonNode contract;

  private EditionContract(JsonNode contract) {
    this.contract = contract;
  }

  static EditionContract read() {
    return new EditionContract(JsonMapper.shared().readTree(FILE.toFile()));
  }

  JsonNode get(String field) {
    return contract.get(field);
  }

  /// One thing found to differ from the contract: its item, `option:--report:choice:xml`, and what
  /// was seen.
  record Found(String item, String what) {}

  /// What fails: each item found that no difference for this edition names, and each difference for
  /// this edition with items on one of `surfaces` (`help`, `error`) none of which was found.
  List<String> unrecorded(List<Found> found, List<String> surfaces) {
    var failures = new ArrayList<String>();
    var mine = mine();
    for (var each : found) {
      if (mine.stream().noneMatch(difference -> names(items(difference), each.item()))) {
        failures.add(
            "%s: %s. Make the Java edition match, or record it under differences in %s"
                    .formatted(each.item(), each.what(), "docs/public/contract/v1.json")
                + " (engine with a reason, dated with an until, pending with an issue)");
      }
    }
    for (var difference : mine) {
      var here = items(difference).stream().filter(item -> on(item, surfaces)).toList();
      if (!here.isEmpty() && found.stream().noneMatch(each -> names(here, each.item()))) {
        failures.add(
            "%s now matches for java: take java out of its editions, or the entry out, in %s"
                .formatted(difference.get("id").asString(), "docs/public/contract/v1.json"));
      }
    }
    return failures;
  }

  /// The differences this edition has.
  private List<JsonNode> mine() {
    return StreamSupport.stream(contract.get("differences").values().spliterator(), false)
        .filter(difference -> strings(difference.get("editions")).contains(EDITION))
        .toList();
  }

  private static boolean on(String item, List<String> surfaces) {
    return surfaces.stream().anyMatch(surface -> item.startsWith(surface + ":"));
  }

  private static List<String> items(JsonNode difference) {
    return difference.has("items") ? strings(difference.get("items")) : List.of();
  }

  static List<String> strings(JsonNode array) {
    return StreamSupport.stream(array.values().spliterator(), false)
        .map(JsonNode::asString)
        .toList();
  }

  private static boolean names(List<String> patterns, String item) {
    return patterns.stream().anyMatch(pattern -> glob(pattern).matcher(item).matches());
  }

  /// An item pattern, `*` matching anything.
  static Pattern glob(String pattern) {
    var regex = new StringBuilder();
    int from = 0;
    for (int star = pattern.indexOf('*'); star >= 0; star = pattern.indexOf('*', from)) {
      regex.append(Pattern.quote(pattern.substring(from, star))).append(".*");
      from = star + 1;
    }
    return Pattern.compile(regex.append(Pattern.quote(pattern.substring(from))).toString());
  }
}
