package io.mutastic.cli;

import java.util.ArrayList;
import java.util.List;

/// A Java or Kotlin source as far as [TestRows] reads it: its annotations, words and braces,
/// without its comments, and with its string literals skipped, since neither holds a declaration.
final class SourceTokens {
  enum Kind {
    ANNOTATION,
    WORD,
    OPEN,
    CLOSE,
    /// Where a declaration's head ends: `(`, `;` or `=`, so the annotations before it are its own.
    END
  }

  /// @param text an annotation's simple name, or the word
  /// @param arguments an annotation's, between its parentheses, without comments: empty if none
  record Token(Kind kind, String text, String arguments) {}

  private final String source;
  private final List<Token> tokens = new ArrayList<>();
  private int at;

  private SourceTokens(String source) {
    this.source = source;
  }

  static List<Token> of(String source) {
    var tokens = new SourceTokens(source);
    while (tokens.at < source.length()) {
      tokens.next();
    }
    return tokens.tokens;
  }

  /// The string literals in a piece of code, their contents as written: a text block's lines too.
  static List<String> strings(String code) {
    var found = new ArrayList<String>();
    var tokens = new SourceTokens(code);
    while (tokens.at < code.length()) {
      int quotes = code.startsWith("\"\"\"", tokens.at) ? 3 : code.charAt(tokens.at) == '"' ? 1 : 0;
      if (quotes > 0) {
        int start = tokens.at + quotes;
        tokens.skipString();
        found.add(code.substring(start, Math.max(start, tokens.at - quotes)));
      } else if (!tokens.skipComment()) {
        tokens.at++;
      }
    }
    return found;
  }

  private void next() {
    if (skipComment()) {
      return;
    }
    char ch = source.charAt(at);
    if (ch == '"' || ch == '\'') {
      skipString();
    } else if (ch == '@') {
      annotation();
    } else if (Character.isJavaIdentifierStart(ch)) {
      int start = at;
      while (at < source.length() && Character.isJavaIdentifierPart(source.charAt(at))) {
        at++;
      }
      tokens.add(new Token(Kind.WORD, source.substring(start, at), ""));
    } else {
      punctuation(ch);
      at++;
    }
  }

  private void punctuation(char ch) {
    switch (ch) {
      case '{' -> tokens.add(new Token(Kind.OPEN, "{", ""));
      case '}' -> tokens.add(new Token(Kind.CLOSE, "}", ""));
      case '(', ';', '=' -> tokens.add(new Token(Kind.END, String.valueOf(ch), ""));
      default -> {
        // nothing a declaration's head needs
      }
    }
  }

  /// `@a.b.Name(…)`, or Kotlin's `@field:Name`: its simple name and what's in its parentheses.
  private void annotation() {
    at++;
    int start = at;
    while (at < source.length() && isNamePart(source.charAt(at))) {
      at++;
    }
    var name = source.substring(start, at);
    name = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf(':')) + 1);
    int after = at;
    while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
      after++;
    }
    var arguments = "";
    if (after < source.length() && source.charAt(after) == '(') {
      at = after + 1;
      arguments = arguments();
    }
    if (!name.isEmpty() && !name.equals("interface")) {
      tokens.add(new Token(Kind.ANNOTATION, name, arguments));
    }
  }

  private static boolean isNamePart(char ch) {
    return Character.isJavaIdentifierPart(ch) || ch == '.' || ch == ':';
  }

  /// What's between an annotation's parentheses, from just after the first, without comments.
  private String arguments() {
    var kept = new StringBuilder();
    int parens = 1;
    while (at < source.length()) {
      if (skipComment()) {
        kept.append(' ');
        continue;
      }
      char ch = source.charAt(at);
      if (ch == '"' || ch == '\'') {
        int literal = at;
        skipString();
        kept.append(source, literal, Math.min(at, source.length()));
        continue;
      }
      parens += ch == '(' ? 1 : ch == ')' ? -1 : 0;
      at++;
      if (parens == 0) {
        break;
      }
      kept.append(ch);
    }
    return kept.toString();
  }

  /// Past a comment, `//` or `/* */` (nested, as Kotlin's may be), if one starts here.
  private boolean skipComment() {
    if (source.startsWith("//", at)) {
      int end = source.indexOf('\n', at);
      at = end < 0 ? source.length() : end;
      return true;
    }
    if (!source.startsWith("/*", at)) {
      return false;
    }
    int nested = 0;
    while (at < source.length()) {
      if (source.startsWith("/*", at) || source.startsWith("*/", at)) {
        nested += source.charAt(at) == '/' ? 1 : -1;
        at += 2;
        if (nested == 0) {
          return true;
        }
      } else {
        at++;
      }
    }
    return true;
  }

  /// Past a string, a text block or raw string, or a character.
  private void skipString() {
    if (source.startsWith("\"\"\"", at)) {
      int end = source.indexOf("\"\"\"", at + 3);
      at = end < 0 ? source.length() : end + 3;
      while (at < source.length() && source.charAt(at) == '"') {
        at++; // Kotlin's raw string may end in more quotes
      }
      return;
    }
    char quote = source.charAt(at++);
    while (at < source.length() && source.charAt(at) != quote) {
      at += source.charAt(at) == '\\' ? 2 : 1;
    }
    at++;
  }
}
