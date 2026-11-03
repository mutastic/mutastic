package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonTest {
  @Test
  void listsAreCommaSeparated() {
    assertThat(Json.write(List.of())).isEqualTo("[]");
    assertThat(Json.write(List.of(1))).isEqualTo("[1]");
    assertThat(Json.write(List.of(1, 2, 3))).isEqualTo("[1,2,3]");
  }

  @Test
  void mapsKeepTheirOrder() {
    var map = new LinkedHashMap<String, Object>();
    map.put("b", true);
    map.put("a", Optional.empty());
    map.put("c", Optional.of("x"));

    assertThat(Json.write(map)).isEqualTo("{\"b\":true,\"a\":null,\"c\":\"x\"}");
    assertThat(Json.write(Map.of())).isEqualTo("{}");
  }

  @Test
  void otherCollectionsAreArraysAndOtherValuesStrings() {
    assertThat(Json.write(Set.of(7))).isEqualTo("[7]");
    assertThat(Json.write(Arrays.asList(1, null))).isEqualTo("[1,null]");
    assertThat(Json.write(java.time.Duration.ofSeconds(2))).isEqualTo("\"PT2S\"");
  }

  @Test
  void stringsEscapeWhatJsonRequires() {
    assertThat(Json.write("a\"b\\c\nd\re\tf\u0001g"))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\"");
    assertThat(Json.write(" ~é")).isEqualTo("\" ~é\"");
  }
}
