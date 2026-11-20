package io.mutastic.cli;

import static io.mutastic.cli.EditionContract.strings;
import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.AridRule;
import io.mutastic.report.OperatorDocs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/// The arid rules the engine has are the contract's (`arid`), and the docs' copy of them,
/// docs/src/data/arid.json, is what both say: the docs page renders it, so it's never typed by
/// hand. `./gradlew :mutastic-cli:exportDocsOutput` writes it again.
class AridRulesTest {
  private static final Path EXPORT =
      Path.of(System.getProperty("mutastic.docsOutput")).resolveSibling("arid.json");
  private static final boolean WRITE = Boolean.getBoolean("mutastic.docsOutput.write");

  private final EditionContract contract = EditionContract.read();

  @Test
  void theEnginesRulesAreTheContracts() {
    var mine = new ArrayList<String>();
    for (var rule : AridRule.values()) {
      mine.add(
          String.join(
              " | ",
              rule.id(),
              rule.scope().written(),
              rule.description(),
              String.join(" / ", rule.reasons()),
              rule.example()));
    }
    var theirs = new ArrayList<String>();
    for (var rule : rules()) {
      if (strings(rule.get("editions")).contains(EditionContract.EDITION)) {
        theirs.add(
            String.join(
                " | ",
                rule.get("id").asString(),
                rule.get("scope").asString(),
                rule.get("description").asString(),
                String.join(" / ", strings(rule.get("reasons").get(EditionContract.EDITION))),
                rule.get("examples").get(EditionContract.EDITION).asString()));
      }
    }

    assertThat(mine)
        .as("the engine's arid rules (AridRule) and the contract's `arid`, for java")
        .containsExactlyElementsOf(theirs);
  }

  /// An edition has a rule when it gives one of its reasons, and shows a line it skips.
  @Test
  void eachRuleHasReasonsForItsEditions() {
    var editions = strings(contract.get("editions"));
    assertThat(rules()).isNotEmpty();
    for (var rule : rules()) {
      var id = rule.get("id").asString();
      assertThat(editions).as(id).containsAll(strings(rule.get("editions")));
      assertThat(rule.get("reasons").propertyNames())
          .as(id)
          .containsExactlyInAnyOrderElementsOf(strings(rule.get("editions")));
      assertThat(rule.get("examples").propertyNames())
          .as(id)
          .containsExactlyInAnyOrderElementsOf(strings(rule.get("editions")));
    }
    assertThat(rules().stream().map(rule -> rule.get("id").asString())).doesNotHaveDuplicates();
  }

  @Test
  void theDocsHaveTheRules() throws IOException {
    var written = exported();
    if (WRITE) {
      Files.writeString(EXPORT, written, StandardCharsets.UTF_8);
    }

    assertThat(Files.readString(EXPORT, StandardCharsets.UTF_8))
        .as("docs/src/data/arid.json: ./gradlew :mutastic-cli:exportDocsOutput")
        .isEqualTo(written);
  }

  /// The `mutastic.yaml` schema's `arid:` takes every edition's ids, the contract's, and only
  /// those.
  @Test
  void theSchemaHasTheIds() throws IOException {
    var schema =
        JsonMapper.shared()
            .readTree(
                EditionContract.FILE.resolveSibling("../schemas/mutastic-yaml/v1.json").toFile());
    var ids =
        schema.findValues("arid").stream()
            .filter(node -> node.has("items"))
            .map(node -> strings(node.get("items").get("enum")))
            .toList();

    assertThat(ids)
        .containsExactly(rules().stream().map(rule -> rule.get("id").asString()).toList());
  }

  /// What the docs render: each rule, the contract's, with its anchor and its section's address.
  private String exported() {
    var mapper = JsonMapper.shared();
    var root = mapper.createObjectNode();
    root.put("schema", "mutastic.arid/v1");
    root.put("page", OperatorDocs.LATEST.base() + AridRule.PAGE);
    var list = root.putArray("rules");
    for (var rule : rules()) {
      var id = rule.get("id").asString();
      var each = list.addObject();
      each.put("id", id);
      each.put("anchor", id.substring(id.indexOf(':') + 1));
      each.put(
          "url", OperatorDocs.LATEST.base() + AridRule.PAGE + "#" + each.get("anchor").asString());
      each.set("editions", rule.get("editions"));
      each.set("scope", rule.get("scope"));
      each.set("description", rule.get("description"));
      each.set("reasons", rule.get("reasons"));
      each.set("examples", rule.get("examples"));
      // What brings the rule's skips back, as a `mutate:` rule's line and as the annotation.
      each.put("mutate", "arid: [" + id + "]");
      each.put("annotation", "@DoMutate(arid = \"" + id + "\")");
      engine(id, each);
    }
    var text = new StringBuilder();
    written(root, "", text);
    return text.append('\n').toString();
  }

  /// JSON as the docs' other data is written: two spaces a level, an item a line.
  private static void written(JsonNode node, String indent, StringBuilder text) {
    var inner = indent + "  ";
    if (node.isObject() && !node.isEmpty()) {
      text.append("{\n");
      var names = new ArrayList<>(node.propertyNames());
      for (int at = 0; at < names.size(); at++) {
        text.append(inner)
            .append(JsonMapper.shared().writeValueAsString(names.get(at)))
            .append(": ");
        written(node.get(names.get(at)), inner, text);
        text.append(at < names.size() - 1 ? ",\n" : "\n");
      }
      text.append(indent).append('}');
    } else if (node.isArray() && !node.isEmpty()) {
      text.append("[\n");
      for (int at = 0; at < node.size(); at++) {
        text.append(inner);
        written(node.get(at), inner, text);
        text.append(at < node.size() - 1 ? ",\n" : "\n");
      }
      text.append(indent).append(']');
    } else {
      text.append(JsonMapper.shared().writeValueAsString(node));
    }
  }

  /// The address the engine gives a rule is the one the docs say.
  private static void engine(String id, ObjectNode each) {
    var rule = Arrays.stream(AridRule.values()).filter(one -> one.id().equals(id)).findFirst();
    rule.ifPresent(
        one ->
            assertThat(one.url(OperatorDocs.LATEST.base()))
                .as(id)
                .isEqualTo(each.get("url").asString()));
  }

  private List<JsonNode> rules() {
    return StreamSupport.stream(contract.get("arid").values().spliterator(), false).toList();
  }
}
