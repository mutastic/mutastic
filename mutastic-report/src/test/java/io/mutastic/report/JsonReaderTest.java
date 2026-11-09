package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// What [Json] writes reads back as it was: a share's events are read back to make the whole run.
class JsonReaderTest {
  @Test
  void whatIsWrittenReadsBack() {
    var event = new LinkedHashMap<String, Object>();
    event.put("text", "a\"b\\c\nd\re\tf\u0001g/é");
    event.put("whole", -12L);
    event.put("fraction", 63.8);
    event.put("flags", List.of(true, false));
    event.put("nested", Map.of("empty", List.of(), "none", Map.of()));

    assertThat(JsonReader.read(Json.write(event))).isEqualTo(event);
  }

  @Test
  void aNullIsLeftOutAndEscapesStandForWhatTheySay() {
    assertThat(JsonReader.read(" {\"score\" : null, \"a\":[1, null]} "))
        .isEqualTo(Map.of("a", List.of(1L)));
    assertThat(JsonReader.read("\"\\u00e9\\/\\b\\f\"")).isEqualTo("é/\b\f");
    assertThat(JsonReader.read("1e2")).isEqualTo(100.0);
  }

  @Test
  void anythingElseIsRefused() {
    assertThatThrownBy(() -> JsonReader.read("{\"a\":1} x")).hasMessage("expected one value at 8");
    assertThatThrownBy(() -> JsonReader.read("{\"a\" 1}")).hasMessage("expected ':' at 5");
    assertThatThrownBy(() -> JsonReader.read("{1:2}")).hasMessage("expected a field's name at 1");
    assertThatThrownBy(() -> JsonReader.read("nope")).hasMessage("expected a value at 0");
    assertThatThrownBy(() -> JsonReader.read("-")).hasMessage("expected a value at 1");
    assertThatThrownBy(() -> JsonReader.read("\"\\u12"))
        .hasMessage("expected four hex digits at 3");
    assertThatThrownBy(() -> JsonReader.read("\"abc")).hasMessage("expected '\"' at 4");
    assertThatThrownBy(() -> JsonReader.read("")).hasMessage("expected a value at 0");
    assertThatThrownBy(() -> JsonReader.read("null")).hasMessage("expected one value at 4");
  }
}
