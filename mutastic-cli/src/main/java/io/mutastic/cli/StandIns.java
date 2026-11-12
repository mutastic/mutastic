package io.mutastic.cli;

import static java.util.Map.entry;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/// A terminal that can't show some of mutastic's characters, as a Windows console on its legacy
/// code page can't show `✓`: each character the terminal's charset can't encode is written as an
/// ASCII stand-in (`✓` as `+`, `…` as `...`), never as the `?` the JDK would write. What it can
/// encode, such as code page 437's box drawing, is written as it is. Written to in UTF-8.
final class StandIns extends OutputStream {
  private static final Map<Character, String> ASCII =
      Map.ofEntries(
          entry('✓', "+"),
          entry('✗', "x"),
          entry('○', "o"),
          entry('⏱', "T"),
          entry('…', "..."),
          entry('·', "-"),
          entry('→', "->"),
          entry('─', "-"),
          entry('│', "|"),
          entry('┌', "+"),
          entry('❯', ">"),
          entry('▲', "^"),
          entry('▼', "v"),
          entry('×', "x"));

  private final PrintStream terminal;
  private final CharsetEncoder encoder;
  private final CharsetDecoder decoder =
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE);

  /// The start of a character a write split, kept for the next.
  private byte[] split = new byte[0];

  private StandIns(PrintStream terminal) {
    this.terminal = terminal;
    this.encoder = terminal.charset().newEncoder();
  }

  /// `terminal`, written to in UTF-8, with a stand-in for each character it can't show.
  static PrintStream over(PrintStream terminal) {
    return new PrintStream(new StandIns(terminal), true, StandardCharsets.UTF_8);
  }

  @Override
  public void write(int b) {
    write(new byte[] {(byte) b}, 0, 1);
  }

  @Override
  public void write(byte[] bytes, int offset, int length) {
    var in =
        ByteBuffer.allocate(split.length + length).put(split).put(bytes, offset, length).flip();
    var text = CharBuffer.allocate(in.remaining());
    decoder.decode(in, text, false);
    split = new byte[in.remaining()];
    in.get(split);
    terminal.print(shown(text.flip()));
  }

  @Override
  public void flush() {
    terminal.flush();
  }

  /// `text` with a stand-in for each character the terminal can't encode.
  String shown(CharSequence text) {
    var shown = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char character = text.charAt(i);
      if (encoder.canEncode(character)) {
        shown.append(character);
      } else {
        shown.append(ASCII.getOrDefault(character, "?"));
      }
    }
    return shown.toString();
  }
}
