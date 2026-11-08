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
/// the root's `allOf` on a `reason` of its own), new words (`description`, `title`, `examples`) and
/// a field marked `deprecated`, which a reader is told of and still gets. Anything else that
/// differs breaks one: a field removed or renamed, a field made required or optional, a type or a
/// `const` changed, an enum value removed, or a constraint added, changed or taken away. References
/// (`$ref`) are compared by what they point to, so moving a definition into `$defs` changes
/// nothing.
///
/// What a schema asks only of the Java edition's events, in a branch on [#JAVA], is compared as if
/// it were asked of every event. That condition matches an event with no `edition` (and one whose
/// `edition` is `java`), and every event a schema from before editions described has none: a field
/// it required stays required of each of them when it moves into that branch, and the Java edition
/// still writes it. So a field other editions don't write moves there without breaking a reader of
/// the format as it was.
final class SchemaCompatibility {
  /// The condition that an event is the Java edition's: it has no `edition`, or `java`.
  static final JsonNode JAVA = Schemas.read("{\"properties\":{\"edition\":{\"const\":\"java\"}}}");

  private static final Set<String> WORDS =
      Set.of("description", "title", "examples", "$comment", "default", "deprecated");

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
    var before = folded(resolved(released, was));
    var after = folded(resolved(current, now));
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

  /// `node` with what it asks of the Java edition's events asked of every event: each branch on
  /// [#JAVA] merged into it.
  private static JsonNode folded(JsonNode node) {
    var branches = node.get("allOf");
    if (branches == null || branches.values().stream().noneMatch(SchemaCompatibility::isJava)) {
      return node;
    }
    var merged = (ObjectNode) node.deepCopy();
    var others = merged.arrayNode();
    branches.values().stream().filter(branch -> !isJava(branch)).forEach(others::add);
    if (others.isEmpty()) {
      merged.remove("allOf");
    } else {
      merged.set("allOf", others);
    }
    branches.values().stream()
        .filter(SchemaCompatibility::isJava)
        .forEach(branch -> merge(merged, branch.get("then")));
    return merged;
  }

  private static boolean isJava(JsonNode branch) {
    return JAVA.equals(branch.get("if"));
  }

  /// Adds what `more` asks to `into`: its required fields to those required, what it asks of a
  /// field to that field, and what it asks under a condition to that condition's branch.
  private static void merge(ObjectNode into, JsonNode more) {
    more.forEachEntry(
        (key, value) -> {
          var there = into.get(key);
          if (there == null) {
            into.set(key, value.deepCopy());
          } else if (key.equals("required")) {
            value.values().stream()
                .filter(field -> !there.values().contains(field))
                .forEach(((ArrayNode) there)::add);
          } else if (key.equals("properties")) {
            value.forEachEntry((field, asked) -> mergeField((ObjectNode) there, field, asked));
          } else if (key.equals("allOf")) {
            value.values().forEach(branch -> mergeBranch((ArrayNode) there, branch));
          } else {
            into.set(key, value.deepCopy());
          }
        });
  }

  private static void mergeField(ObjectNode fields, String field, JsonNode asked) {
    if (fields.get(field) instanceof ObjectNode known) {
      merge(known, asked);
    } else {
      fields.set(field, asked.deepCopy());
    }
  }

  private static void mergeBranch(ArrayNode branches, JsonNode branch) {
    var same =
        branches.values().stream().filter(it -> branch.get("if").equals(it.get("if"))).findFirst();
    if (same.isPresent() && same.get().get("then") instanceof ObjectNode then) {
      merge(then, branch.get("then"));
    } else {
      branches.add(branch.deepCopy());
    }
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
