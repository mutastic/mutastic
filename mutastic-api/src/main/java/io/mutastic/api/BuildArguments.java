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
    var split = new ArrayList<String>();
    var word = new StringBuilder();
    boolean started = false;
    char quote = 0;
    for (int at = 0; at < words.length(); at++) {
      char next = words.charAt(at);
      if (quote != 0 && next == quote) {
        quote = 0;
      } else if (quote == 0 && (next == '\'' || next == '"')) {
        quote = next;
        started = true;
      } else if (quote == 0 && Character.isWhitespace(next)) {
        if (started) {
          split.add(word.toString());
        }
        word.setLength(0);
        started = false;
      } else {
        word.append(next);
        started = true;
      }
    }
    if (quote != 0) {
      throw new IllegalArgumentException("a %c isn't closed".formatted(quote));
    }
    if (started) {
      split.add(word.toString());
    }
    return List.copyOf(split);
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
