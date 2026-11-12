package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/// A Windows console on code page 437 shows mutastic's progress with ASCII stand-ins where it has
/// no glyph, and its own where it has one, never `?`.
class StandInsTest {
  private static final Charset CONSOLE = Charset.forName("IBM437");

  @Test
  void whatTheConsoleCantShowIsWrittenInAscii() {
    var shown = new ByteArrayOutputStream();
    var out = StandIns.over(new PrintStream(shown, true, CONSOLE));

    out.print(
        """
        [3/4] ✓ 3 caught · ✗ 1 missed · ○ 0 not covered · ⏱ 0 timed out…
             ┌─ src/Rates.java:9
        """);

    assertThat(shown.toString(CONSOLE))
        .isEqualTo(
            """
            [3/4] + 3 caught · x 1 missed · o 0 not covered · T 0 timed out...
                 ┌─ src/Rates.java:9
            """);
  }

  /// A character whose UTF-8 bytes arrive in two writes is still one character.
  @Test
  void aCharacterSplitOverTwoWritesIsOne() {
    var shown = new ByteArrayOutputStream();
    var out = StandIns.over(new PrintStream(shown, true, CONSOLE));
    var tick = "✓ caught".getBytes(StandardCharsets.UTF_8);

    out.write(tick, 0, 2);
    out.write(tick, 2, tick.length - 2);
    out.flush();

    assertThat(shown.toString(CONSOLE)).isEqualTo("+ caught");
  }

  @Test
  void aTerminalInUtf8IsLeftAsItIs() {
    var shown = new ByteArrayOutputStream();
    var out = StandIns.over(new PrintStream(shown, true, StandardCharsets.UTF_8));

    out.print("✓ caught · ⏱ timed out");

    assertThat(shown.toString(StandardCharsets.UTF_8)).isEqualTo("✓ caught · ⏱ timed out");
  }
}
