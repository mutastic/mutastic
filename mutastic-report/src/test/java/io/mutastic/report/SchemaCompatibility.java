package io.mutastic.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/// What a schema breaks of a released one's promises: everything a reader of the released format
/// could count on, and can't now.
///
/// The changes that keep them are a new optional field, a new enum value, a new event (a branch of
/// the root's `allOf` on a `reason` of its own) and new words (`description`, `title`, `examples`).
/// Anything else that differs breaks one: a field removed or renamed, a field made required or
/// optional, a type or a `const` changed, an enum value removed, or a constraint added, changed or
/// taken away. References (`$ref`) are compared by what they point to, so moving a definition into
/// `$defs` changes nothing.
final class SchemaCompatibility {
  private static final Set<String> WORDS =
      Set.of("description", "title", "examples", "$comment", "default");

  private final JsonNode released;
  private final JsonNode current;
  private final List<String> breaks = new ArrayList<>();

  private SchemaCompatibility(JsonNode released, JsonNode current) {
    this.released = released;
    this.current = current;
  }

  /// Each promise `current` breaks of `released`'s, in words, with where: empty when it keeps them.
  static List<String> breaks(JsonNode released, JsonNode current) {
    var compatibility = new SchemaCompatibility(released, current);
    compatibility.compare("", released, current);
    return compatibility.breaks;
  }

  private void compare(String at, JsonNode was, JsonNode now) {
    var before = resolved(released, was);
    var after = resolved(current, now);
    var keywords = new LinkedHashSet<>(before.propertyNames());
    keywords.addAll(after.propertyNames());
    keywords.removeAll(WORDS);
    keywords.remove("$defs");
    for (String keyword : keywords) {
      var then = before.get(keyword);
      var later = after.get(keyword);
      if (then == null) {
        breaks.add("%s: `%s` added".formatted(place(at), keyword));
      } else if (later == null) {
        breaks.add("%s: `%s` removed".formatted(place(at), keyword));
      } else {
        compare(at, keyword, then, later);
      }
    }
  }

  private void compare(String at, String keyword, JsonNode then, JsonNode later) {
    switch (keyword) {
      case "properties" -> properties(at, then, later);
      case "enum" -> values(at, then, later);
      case "required", "type" -> same(at, keyword, words(then), words(later));
      case "allOf" -> branches(at, then, later);
      default -> {
        if (then.isObject() && later.isObject()) {
          compare(at + "/" + keyword, then, later);
        } else if (!then.equals(later)) {
          breaks.add("%s: `%s` changed from %s to %s".formatted(place(at), keyword, then, later));
        }
      }
    }
  }

  /// Every field the released schema names is still there, and as the released one says.
  private void properties(String at, JsonNode then, JsonNode later) {
    for (String field : then.propertyNames()) {
      if (later.has(field)) {
        compare(at + "/" + field, then.get(field), later.get(field));
      } else {
        breaks.add("%s: the field `%s` is gone".formatted(place(at), field));
      }
    }
  }

  /// An enum may gain values, never lose one.
  private void values(String at, JsonNode then, JsonNode later) {
    var kept = later.values();
    for (JsonNode value : then.values()) {
      if (!kept.contains(value)) {
        breaks.add("%s: the value %s is gone".formatted(place(at), value));
      }
    }
  }

  private void same(String at, String keyword, Set<String> then, Set<String> later) {
    if (!then.equals(later)) {
      breaks.add("%s: `%s` changed from %s to %s".formatted(place(at), keyword, then, later));
    }
  }

  /// Each released branch, found by its condition, still says what it said. A new branch is a new
  /// event when its condition is a `reason`, which readers ignore until they know it; any other
  /// asks more of an event that was valid.
  private void branches(String at, JsonNode then, JsonNode later) {
    for (JsonNode branch : then.values()) {
      var condition = branch.get("if");
      var match = later.values().stream().filter(it -> condition.equals(it.get("if"))).findFirst();
      if (match.isPresent()) {
        compare(at + "/if " + condition, branch.get("then"), match.get().get("then"));
      } else {
        breaks.add("%s: the branch for %s is gone".formatted(place(at), condition));
      }
    }
    for (JsonNode branch : later.values()) {
      var condition = branch.get("if");
      boolean known = then.values().stream().anyMatch(it -> condition.equals(it.get("if")));
      if (!known && !condition.path("properties").has("reason")) {
        breaks.add("%s: a branch for %s added".formatted(place(at), condition));
      }
    }
  }

  /// The schema `node` points to, with what's beside its `$ref`: words, or more fields required.
  private static JsonNode resolved(JsonNode root, JsonNode node) {
    var reference = node.get("$ref");
    if (reference == null) {
      return node;
    }
    var target = (ObjectNode) resolved(root, root.at(reference.stringValue().substring(1)));
    var merged = target.deepCopy();
    node.forEachEntry(
        (key, value) -> {
          if (key.equals("required") && merged.has("required")) {
            ((ArrayNode) merged.get("required")).addAll((ArrayNode) value);
          } else if (!key.equals("$ref")) {
            merged.set(key, value);
          }
        });
    return merged;
  }

  /// `required` or `type` as a set: its order, or one type written without brackets, is no change.
  private static Set<String> words(JsonNode node) {
    var words = new TreeSet<String>();
    if (node.isArray()) {
      node.values().forEach(word -> words.add(word.asString()));
    } else {
      words.add(node.asString());
    }
    return words;
  }

  private static String place(String at) {
    return at.isEmpty() ? "the event" : at;
  }
}
