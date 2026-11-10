package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/// From 1.0.0 the events format only grows within a major version: the schema docs.mutastic.io
/// serves keeps every promise of the last 1.0+ release's, kept in `released/events-v1.json`.
/// `lastStableRelease` (gradle.properties) names that release, and a release copies its published
/// schema there (RELEASING.md), so a field added since can still change until it ships.
///
/// Before 1.0.0 the schema may change; from 1.0.0 it only gains. So until a 1.0+ release names a
/// baseline, there's nothing to compare with, and no earlier release's schema may stand in for one.
class SchemaCompatibilityTest {
  private static final String LAST_STABLE = System.getProperty("mutastic.lastStableRelease", "");
  private static final JsonNode PUBLISHED = Schemas.read(Schemas.EVENTS);

  @Test
  void thePublishedSchemaKeepsTheLastStableReleasesPromises() throws IOException {
    try (var released =
        SchemaCompatibilityTest.class.getResourceAsStream("released/events-v1.json")) {
      if (LAST_STABLE.isEmpty()) {
        assertThat(released)
            .as("released/events-v1.json, with no 1.0+ release to hold (lastStableRelease)")
            .isNull();
        return;
      }
      assertThat(released)
          .as("lastStableRelease is %s: copy its schema to released/ (RELEASING.md)", LAST_STABLE)
          .isNotNull();
      assertThat(SchemaCompatibility.breaks(Schemas.read(released), PUBLISHED))
          .as(
              "docs/public/schemas/events/v1.json against %s's: a v1 reader would break",
              LAST_STABLE)
          .isEmpty();
    }
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

  /// A field only the Java edition writes is still required of every event without an `edition`,
  /// which is every event a schema from before editions described.
  @Test
  void aRequirementMovedUnderTheJavaEditionKeepsThem() {
    assertThat(breaks(schema -> onlyInJava(schema, "durationMs"))).isEmpty();
    assertThat(breaks(schema -> javaAlsoRequires(schema, "aridAudit")))
        .isNotEmpty()
        .allMatch(problem -> problem.contains("`required` changed"));
  }

  /// Under any other condition, it's no longer required of every event it was required of.
  @Test
  void aRequirementMovedUnderAnotherEditionBreaksThem() {
    assertThat(
            breaks(
                schema -> {
                  onlyInJava(schema, "durationMs");
                  var branch = (ObjectNode) finished(schema).get("allOf").get(1);
                  branch.set(
                      "if", Schemas.read("{\"properties\":{\"edition\":{\"const\":\"rust\"}}}"));
                }))
        .isNotEmpty();
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

  private static ObjectNode finished(ObjectNode schema) {
    return def(schema, "runFinished");
  }

  /// `run-finished`'s `field` required only of the Java edition, in a branch of its own.
  private static void onlyInJava(ObjectNode schema, String field) {
    var required = (ArrayNode) finished(schema).get("required");
    for (int at = required.size() - 1; at >= 0; at--) {
      if (required.get(at).asString().equals(field)) {
        required.remove(at);
      }
    }
    javaAlsoRequires(schema, field);
  }

  private static void javaAlsoRequires(ObjectNode schema, String field) {
    var branch = finished(schema).withArray("allOf").addObject();
    branch.set("if", SchemaCompatibility.JAVA.deepCopy());
    branch.putObject("then").putArray("required").add(field);
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
