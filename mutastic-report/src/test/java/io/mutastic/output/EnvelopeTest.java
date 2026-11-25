package io.mutastic.output;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class EnvelopeTest {
  @Test
  void anEventStartsWithItsSchemaEditionAndReason() {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("phase", "coverage");
    fields.put("done", 3);

    assertThat(Envelope.line("tool.events/v1", "java", "progress", fields))
        .isEqualTo(
            "{\"schema\":\"tool.events/v1\",\"edition\":\"java\",\"reason\":\"progress\","
                + "\"phase\":\"coverage\",\"done\":3}\n");
  }
}
