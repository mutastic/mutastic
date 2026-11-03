package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The docs' model of the commands and options, as `exportDocsModel` writes it: the same as the
/// committed copy the reference is built from, with every command, option, variable and exit code.
class DocsModelTest {
  private static final Path COMMITTED =
      Path.of(System.getProperty("mutastic.docs")).resolve("../../data/commands.json").normalize();

  @TempDir Path work;

  @Test
  void theModelIsTheCommittedOne() throws Exception {
    var written = work.resolve("commands.json");

    DocsModel.main(new String[] {written.toString()});

    var model = Files.readString(written, StandardCharsets.UTF_8);
    assertThat(model).isEqualTo(Files.readString(COMMITTED, StandardCharsets.UTF_8));
    assertThat(model)
        .contains("\"schema\": \"mutastic.commands/v1\"")
        .contains("\"name\": \"--operators\"")
        .contains("\"name\": \"--suite\"")
        .contains("\"name\": \"--exclude-tests\"")
        .contains("\"name\": \"MUTASTIC_FORMAT\"")
        .contains("\"name\": \"4\"");
  }
}
