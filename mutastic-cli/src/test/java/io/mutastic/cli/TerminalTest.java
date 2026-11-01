package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalTest {
  private static final Terminal TTY = new Terminal(true, Map.of());
  private static final Terminal PIPE = new Terminal(false, Map.of());

  @Test
  void peopleGetCardsLogsGetLinesAgentsGetAgentText() {
    assertThat(TTY.defaultFormat()).isEqualTo("full");
    assertThat(PIPE.defaultFormat()).isEqualTo("concise");
    assertThat(new Terminal(true, Map.of("MUTASTIC_AGENT", "1")).defaultFormat()).isEqualTo("agent");
  }

  @Test
  void colourOnlyOnATerminalThatWantsIt() {
    assertThat(TTY.colour("auto")).isTrue();
    assertThat(PIPE.colour("auto")).isFalse();
    assertThat(new Terminal(true, Map.of("NO_COLOR", "")).colour("auto")).isFalse();
    assertThat(new Terminal(true, Map.of("TERM", "dumb")).colour("auto")).isFalse();
    assertThat(new Terminal(false, Map.of("FORCE_COLOR", "1")).colour("auto")).isTrue();
    assertThat(PIPE.colour("always")).isTrue();
    assertThat(TTY.colour("never")).isFalse();
  }

  @Test
  void linksOnlyWhereAPersonCanClickThem() {
    assertThat(TTY.links("auto")).isTrue();
    assertThat(PIPE.links("auto")).isFalse();
    assertThat(new Terminal(true, Map.of("TERM", "dumb")).links("auto")).isFalse();
    assertThat(PIPE.links("always")).isTrue();
  }
}
