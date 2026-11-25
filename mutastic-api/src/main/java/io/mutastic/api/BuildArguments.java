package io.mutastic.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// The arguments the build tool gets on each build call, and those of them typed on the command
/// line, which the commands mutastic offers repeat: one from a variable or a file applies by itself
/// and may be a secret, so it's never repeated.
///
/// @param words what the build tool gets, in order
/// @param typed the arguments typed after `--`: empty when none were
@Internal
public record BuildArguments(List<String> words, Optional<List<String>> typed) {
  /// No arguments, and none typed.
  public static final BuildArguments NONE = new BuildArguments(List.of(), Optional.empty());

  /// A word a shell reads as it is.
  private static final Pattern PLAIN = Pattern.compile("[\\w./:=,@%+-]+");

  public BuildArguments {
    words = List.copyOf(words);
    typed = typed.map(List::copyOf);
  }

  /// `arguments`, typed after `--`.
  public static BuildArguments typed(List<String> arguments) {
    return new BuildArguments(arguments, Optional.of(arguments));
  }

  /// `arguments`, from a variable or a file.
  public static BuildArguments quiet(List<String> arguments) {
    return new BuildArguments(arguments, Optional.empty());
  }

  /// `words` split as a person writes them in a variable: at spaces, but not inside single or
  /// double quotes, which go. A backslash is kept, so a Windows path needs no care, and nothing is
  /// expanded.
  ///
  /// @throws IllegalArgumentException when a quote isn't closed
  public static List<String> split(String words) {
    var reading = new Splitting();
    for (int at = 0; at < words.length(); at++) {
      reading.next(words.charAt(at));
    }
    return reading.split();
  }

  /// Words being split, a character at a time.
  private static final class Splitting {
    private static final char NONE = 0;

    private final List<String> split = new ArrayList<>();
    private final StringBuilder word = new StringBuilder();
    private boolean started = false;
    private char quote = NONE;

    void next(char next) {
      if (quote != NONE) {
        inside(next);
      } else if (next == '\'' || next == '"') {
        quote = next;
        started = true;
      } else if (Character.isWhitespace(next)) {
        end();
      } else {
        word.append(next);
        started = true;
      }
    }

    private void inside(char next) {
      if (next == quote) {
        quote = NONE;
      } else {
        word.append(next);
      }
    }

    private void end() {
      if (started) {
        split.add(word.toString());
      }
      word.setLength(0);
      started = false;
    }

    List<String> split() {
      if (quote != NONE) {
        throw new IllegalArgumentException("a %c isn't closed".formatted(quote));
      }
      end();
      return List.copyOf(split);
    }
  }

  /// `word` as a shell reads it back.
  public static String quoted(String word) {
    return PLAIN.matcher(word).matches() ? word : "'" + word.replace("'", "'\\''") + "'";
  }

  /// ` -- -Pci --offline`, the end of each of mutastic's commands that's offered: empty when none
  /// were typed.
  public String ending() {
    return typed.map(BuildArguments::ending).orElse("");
  }

  /// ` -- -Pci --offline`.
  public static String ending(List<String> arguments) {
    var ending = new StringBuilder(" --");
    arguments.forEach(word -> ending.append(' ').append(quoted(word)));
    return ending.toString();
  }

  /// ` -Pci --offline`, the end of a build's own command that's offered.
  public String buildEnding() {
    var ending = new StringBuilder();
    typed.orElse(List.of()).forEach(word -> ending.append(' ').append(quoted(word)));
    return ending.toString();
  }

  /// An argument mutastic can't give a build tool.
  ///
  /// @param name the argument without its value, `--tests`: a value may be a secret
  /// @param reason why not, as the rest of a sentence: `mutastic chooses the tests it runs`
  public record Refused(String name, String reason) {}
}
