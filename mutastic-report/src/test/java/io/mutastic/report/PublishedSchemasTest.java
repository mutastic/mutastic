package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Each schema docs.mutastic.io serves is a JSON Schema (2020-12), and says where it's served: its
/// `$id` is its address, so a reader that follows one finds the same file.
class PublishedSchemasTest {
  private static final String SITE = "https://docs.mutastic.io/schemas/";

  @Test
  void eachIsASchemaWhoseIdIsWhereItIsServed() throws Exception {
    var metaSchema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    List<Path> published;
    try (Stream<Path> files = Files.walk(Schemas.PUBLISHED)) {
      published = files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
    }

    assertThat(published)
        .extracting(file -> Schemas.PUBLISHED.relativize(file).toString())
        .containsExactly(
            "contract/v1.json", "events/v1.json", "mutastic-yaml/v1.json", "rules/v1.json");
    for (Path file : published) {
      var schema = Schemas.read(file);
      var served = Schemas.PUBLISHED.relativize(file).toString();
      assertThat(schema.get("$id").asString()).as(served).isEqualTo(SITE + served);
      assertThat(schema.get("$schema").asString())
          .as(served)
          .isEqualTo("https://json-schema.org/draft/2020-12/schema");
      assertThat(Schemas.problems(metaSchema, Files.readString(file))).as(served).isEmpty();
    }
  }
}
