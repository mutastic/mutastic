package io.mutastic.output;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RemediesTest {
  private static final Pattern TOOL = Pattern.compile("^  tool ");

  @Test
  void eachCommandIsGradedBySureness() {
    var said =
        """
        tool: there's no option --formt. There's one called --format.
          tool check --format=text
          tool check <directory>
          tool list
        """;

    assertThat(Remedies.in(said, TOOL))
        .containsExactly(
            Map.of("applicability", "maybe", "command", "tool check --format=text"),
            Map.of("applicability", "placeholders", "command", "tool check <directory>"),
            Map.of("applicability", "exact", "command", "tool list"));
  }
}
