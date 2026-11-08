package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/// The schemas docs.mutastic.io serves (`docs/public/schemas`), and the checks the tests hold
/// mutastic's output and inputs to with them. The CLI's tests use them too.
///
/// The published events schema leaves objects open, since new fields are how the format grows and a
/// reader should take an event with one it doesn't know. The tests read it strictly: every object
/// it describes closed, and only the event reasons it describes allowed. So a field mutastic writes
/// that the schema doesn't name, a typo or a new field nobody described, fails the test that wrote
/// it.
public final class Schemas {
  static final Path PUBLISHED = Path.of(System.getProperty("mutastic.schemas"));
  static final Path EVENTS = PUBLISHED.resolve("events/v1.json");

  private static final Schema STRICT = schema(strict(read(EVENTS)));

  private Schemas() {}

  static JsonNode read(Path file) {
    return JsonMapper.shared().readTree(file.toFile());
  }

  static JsonNode read(java.io.InputStream json) {
    return JsonMapper.shared().readTree(json);
  }

  static JsonNode read(String json) {
    return JsonMapper.shared().readTree(json);
  }

  /// A schema of its own registry, so two readings of one `$id` don't meet. Its `$schema` says
  /// which draft it is.
  static Schema schema(JsonNode schema) {
    return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);
  }

  /// That each line of `output` is an event the schema describes in full.
  public static void checkEvents(String output) {
    output
        .lines()
        .forEach(line -> assertThat(problems(STRICT, line)).as("an event: %s", line).isEmpty());
  }

  /// What the published schema at `path` (`mutastic-yaml/v1.json`) finds wrong with `yaml`.
  public static List<String> yamlProblems(String path, String yaml) {
    return schema(read(PUBLISHED.resolve(path))).validate(yaml, InputFormat.YAML).stream()
        .map(Error::toString)
        .toList();
  }

  /// What the mutation-testing report schema, at the version mutation.json claims, finds wrong with
  /// `json`: its own copy, in test resources (THIRD-PARTY.md says from where).
  public static List<String> reportProblems(String json) {
    return problems(report(), json);
  }

  static Schema report() {
    return schema(
        read(Schemas.class.getResourceAsStream("schema/mutation-testing-report-schema.json")));
  }

  static List<String> problems(Schema schema, String json) {
    return schema.validate(json, InputFormat.JSON).stream().map(Error::toString).toList();
  }

  /// The published schema with every object it describes closed, and only its reasons allowed.
  static JsonNode strict(JsonNode published) {
    var strict = (ObjectNode) published.deepCopy();
    close(strict.get("$defs"));
    var reasons = strict.arrayNode();
    strict
        .get("allOf")
        .forEach(
            branch -> reasons.add(branch.get("if").get("properties").get("reason").get("const")));
    ((ObjectNode) strict.get("properties").get("reason")).set("enum", reasons);
    return strict;
  }

  /// No properties but those named, in `node` and every object schema under it. A condition (`if`)
  /// is left as it is: closed, it would stop matching, and what it guards would go unchecked. What
  /// a condition asks (`then`) is only more of the object it's in, so it isn't closed itself, or it
  /// would refuse every field it doesn't name again; what's under it is.
  private static void close(JsonNode node) {
    if (node instanceof ObjectNode object) {
      if (object.has("properties") && !object.has("additionalProperties")) {
        object.put("additionalProperties", false);
      }
      closeWithin(object);
    } else if (node.isArray()) {
      node.values().forEach(Schemas::close);
    }
  }

  private static void closeWithin(ObjectNode object) {
    object.forEachEntry(
        (key, child) -> {
          if (key.equals("then")) {
            overlay(child);
          } else if (!key.equals("if")) {
            close(child);
          }
        });
  }

  /// What a `then` asks of the object it's in, and of that object's fields: more of each, so none
  /// of them closed.
  private static void overlay(JsonNode then) {
    then.forEachEntry(
        (key, child) -> {
          if (key.equals("properties")) {
            child.values().forEach(Schemas::overlay);
          } else {
            close(child);
          }
        });
  }
}
