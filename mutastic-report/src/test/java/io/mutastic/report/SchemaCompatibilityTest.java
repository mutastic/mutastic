package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/// The events format only grows within a major version: the schema docs.mutastic.io serves keeps
/// every promise of the last release's, kept in `released/events-v1.json`. A release copies the
/// published schema there (RELEASING.md), so a field added since can still change until it ships.
class SchemaCompatibilityTest {
  private static final JsonNode RELEASED =
      Schemas.read(SchemaCompatibilityTest.class.getResourceAsStream("released/events-v1.json"));
  private static final JsonNode PUBLISHED = Schemas.read(Schemas.EVENTS);

  @Test
  void thePublishedSchemaKeepsTheLastReleasesPromises() {
    assertThat(SchemaCompatibility.breaks(RELEASED, PUBLISHED))
        .as(
            "docs/public/schemas/events/v1.json against the last release's: a v1 reader would break")
        .isEmpty();
  }

  @Test
  void growingKeepsThem() {
    assertThat(breaks(schema -> fields(schema).putObject("caughtWhere").put("type", "string")))
        .as("a new optional field")
        .isEmpty();
    assertThat(breaks(schema -> ((ArrayNode) def(schema, "status").get("enum")).add("Pending")))
        .as("a new status")
        .isEmpty();
    assertThat(breaks(schema -> newEvent(schema, "coverage-finished"))).as("a new event").isEmpty();
    assertThat(breaks(schema -> def(schema, "status").put("description", "Other words.")))
        .as("new words")
        .isEmpty();
    assertThat(breaks(schema -> ((ObjectNode) fields(schema).get("id")).put("deprecated", true)))
        .as("a field deprecated, and still written")
        .isEmpty();
  }

  @Test
  void removingOrRenamingAFieldBreaksThem() {
    assertThat(breaks(schema -> fields(schema).remove("killedBy")))
        .contains("/if " + reason("mutant-finished") + ": the field `killedBy` is gone")
        .allMatch(problem -> problem.endsWith("the field `killedBy` is gone"));
    assertThat(breaks(schema -> fields(schema).set("caughtBy", fields(schema).remove("killedBy"))))
        .contains("/if " + reason("mutant-finished") + ": the field `killedBy` is gone")
        .allMatch(problem -> problem.endsWith("the field `killedBy` is gone"));
  }

  @Test
  void requiringAnOptionalFieldBreaksThem() {
    assertThat(breaks(schema -> ((ArrayNode) mutant(schema).get("required")).add("killedBy")))
        .isNotEmpty()
        .allMatch(problem -> problem.contains("`required` changed"));
  }

  @Test
  void narrowingATypeOrAnEnumBreaksThem() {
    assertThat(breaks(schema -> def(schema, "score").put("type", "number")))
        .isNotEmpty()
        .allMatch(problem -> problem.contains("`type` changed from [null, number] to [number]"));
    assertThat(breaks(schema -> ((ArrayNode) def(schema, "status").get("enum")).remove(4)))
        .isNotEmpty()
        .allMatch(problem -> problem.contains("the value \"Ignored\" is gone"));
  }

  @Test
  void anEventGoneBreaksThem() {
    assertThat(breaks(schema -> ((ArrayNode) schema.get("allOf")).remove(0)))
        .containsExactly("the event: the branch for " + reason("run-started") + " is gone");
  }

  /// What the published schema breaks once `change` is made to a copy of it.
  private static java.util.List<String> breaks(Consumer<ObjectNode> change) {
    var changed = (ObjectNode) PUBLISHED.deepCopy();
    change.accept(changed);
    return SchemaCompatibility.breaks(PUBLISHED, changed);
  }

  private static ObjectNode def(ObjectNode schema, String name) {
    return (ObjectNode) schema.get("$defs").get(name);
  }

  private static ObjectNode mutant(ObjectNode schema) {
    return def(schema, "mutantFinished");
  }

  private static ObjectNode fields(ObjectNode schema) {
    return (ObjectNode) mutant(schema).get("properties");
  }

  private static void newEvent(ObjectNode schema, String reason) {
    var branch = ((ArrayNode) schema.get("allOf")).addObject();
    branch.set("if", Schemas.read(reason(reason)));
    branch.putObject("then").putArray("required").add("schema");
  }

  /// A branch's condition, as the schema writes it and a break names it.
  private static String reason(String reason) {
    return "{\"properties\":{\"reason\":{\"const\":\"%s\"}},\"required\":[\"reason\"]}"
        .formatted(reason);
  }
}
