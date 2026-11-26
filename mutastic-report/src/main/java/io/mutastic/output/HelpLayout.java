package io.mutastic.output;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// How help is laid out: 100 columns, text wrapped between words, an option's meaning in a column
/// of its own, a section's names in a column as wide as the longest, and an example broken as a
/// shell still takes it.
public final class HelpLayout {
  private HelpLayout() {}

  /// The widest a line of help is.
  public static final int WIDTH = 100;

  /// Where an option's meaning starts.
  public static final int COLUMN = 26;

  /// A name and what it means, in a section: an exit status, an environment variable.
  public record Term(String name, String meaning) {}

  /// ` --threshold=N` and what it means from [#COLUMN] on, wrapped there; an option too wide for
  /// the column has a line of its own.
  public static List<String> option(String synopsis, String meaning) {
    var out = new ArrayList<String>();
    var first = "  " + synopsis;
    if (first.length() >= COLUMN) {
      out.add(first);
      first = "";
    }
    for (String line : wrapped(meaning, WIDTH - COLUMN)) {
      out.add(padded(first, COLUMN) + line);
      first = "";
    }
    return out;
  }

  /// An example, broken before an option where it's wider than help: each line but the last ends in
  /// ` \`, and the rest is indented under it, as a shell takes it.
  public static List<String> example(String example) {
    var out = new ArrayList<String>();
    var line = new StringBuilder("  ");
    boolean empty = true;
    for (String part : example.split(" (?=--)", -1)) {
      if (!empty && line.length() + 1 + part.length() + 2 > WIDTH) {
        out.add(line.append(" \\").toString());
        line.setLength(0);
        line.append("      ");
      } else if (!empty) {
        line.append(' ');
      }
      line.append(part);
      empty = false;
    }
    out.add(line.toString());
    return out;
  }

  /// A heading and its terms, each meaning wrapped under its first line.
  public static List<String> section(String heading, List<Term> terms) {
    int width = terms.stream().mapToInt(term -> term.name().length()).max().orElse(0) + 2;
    var indent = " ".repeat(2 + width);
    var out = new ArrayList<String>();
    out.add("");
    out.add(heading);
    for (Term term : terms) {
      var lines = wrapped(term.meaning(), WIDTH - indent.length());
      out.add("  " + padded(term.name(), width) + lines.getFirst());
      lines.stream().skip(1).forEach(line -> out.add(indent + line));
    }
    return out;
  }

  /// `text` in lines of at most `width` characters, broken between words.
  public static List<String> wrapped(String text, int width) {
    var lines = new ArrayList<String>();
    var line = new StringBuilder();
    for (String word : Arrays.asList(text.split(" ", -1))) {
      if (line.length() > 0 && line.length() + 1 + word.length() > width) {
        lines.add(line.toString());
        line.setLength(0);
      }
      line.append(line.length() > 0 ? " " : "").append(word);
    }
    lines.add(line.toString());
    return lines;
  }

  /// `text` padded to `width`, or with one space after it when it's as wide or wider.
  public static String padded(String text, int width) {
    return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
  }
}
