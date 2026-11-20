package io.mutastic.engine;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/// `@Generated` that only the source has. `javax.annotation.processing.Generated`, and
/// `javax.annotation.Generated` and `jakarta.annotation.Generated` before it, are kept for the
/// source alone, so the class file never holds them; annotation processors (Dagger, MapStruct,
/// AutoValue, Immutables) and code generators (OpenAPI Generator, jOOQ) put them on the types they
/// write. Read from a Java class's source file: the outermost type it's in is generated when its
/// declaration there carries an annotation called `Generated`, in any package, as [AnnotatedSkips]
/// reads them. Comments, strings and characters are blanked first, so text that only mentions it
/// never counts; a member's own `@Generated` isn't read.
final class SourceGenerated {
  /// None: for a run with no source roots.
  static final SourceGenerated NONE = new SourceGenerated(List.of());

  /// `class Name`, `interface Name`, `enum Name`, `record Name`: a type's declaration.
  private static final Pattern DECLARATION =
      Pattern.compile(
          "\\b(?:class|interface|enum|record)\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)");

  /// `@Generated`, `@javax.annotation.processing.Generated`: not `@GeneratedValue`.
  private static final Pattern GENERATED =
      Pattern.compile(
          "@\\s*(?:[\\p{javaJavaIdentifierPart}]+\\s*\\.\\s*)*Generated(?![\\p{javaJavaIdentifierPart}])");

  private final List<Path> roots;

  /// Each class's answer, by internal name: asked once per method, read once per class.
  private final Map<String, Boolean> byClass = new ConcurrentHashMap<>();

  SourceGenerated(List<Path> roots) {
    this.roots = List.copyOf(roots);
  }

  /// Whether the outermost type `model` is in has `@Generated` on it in its source file.
  boolean marks(ClassModel model) {
    if (roots.isEmpty()) {
      return false;
    }
    return byClass.computeIfAbsent(model.thisClass().asInternalName(), name -> read(model));
  }

  /// `Outer` for `com/acme/Outer$Inner` or `com/acme/Outer$1`: the type the file declares.
  static String outermost(String internalName) {
    var simple = internalName.substring(internalName.lastIndexOf('/') + 1);
    int dollar = simple.indexOf('$');
    return dollar > 0 ? simple.substring(0, dollar) : simple;
  }

  private boolean read(ClassModel model) {
    boolean java =
        model
            .findAttribute(Attributes.sourceFile())
            .filter(file -> file.sourceFile().stringValue().endsWith(".java"))
            .isPresent();
    if (!java) {
      return false;
    }
    return SourcePaths.of(model, roots)
        .map(file -> generatedTypes(text(file)))
        .filter(types -> types.contains(outermost(model.thisClass().asInternalName())))
        .isPresent();
  }

  private static String text(Path file) {
    try {
      // Decoded leniently: a byte that isn't UTF-8 is replaced, never a failure.
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  /// The names of the top-level types the source declares with `@Generated` on them.
  static Set<String> generatedTypes(String source) {
    var code = Blanked.of(source);
    var found = new HashSet<String>();
    var nesting = new Nesting();
    for (int at = 0; at < code.length(); at++) {
      if (nesting.opensATypeBody(code.charAt(at), at)) {
        header(code.substring(nesting.headerStart, at), found);
      }
    }
    return found;
  }

  /// How deep the scan is in braces, outside parentheses (an annotation's `{…}` array is in them),
  /// and where the code since the last top-level `;` or `}` starts: a top-level type's header.
  private static final class Nesting {
    private int depth;
    private int parens;
    private int headerStart;

    /// Takes the character at `at`; whether it's the `{` of a top-level type's body.
    boolean opensATypeBody(char each, int at) {
      switch (each) {
        case '(' -> parens++;
        case ')' -> parens = Math.max(0, parens - 1);
        case '{' -> {
          if (parens == 0) {
            return depth++ == 0;
          }
        }
        case '}' -> {
          if (parens == 0 && depth > 0 && --depth == 0) {
            headerStart = at + 1;
          }
        }
        case ';' -> {
          if (parens == 0 && depth == 0) {
            headerStart = at + 1;
          }
        }
        default -> {}
      }
      return false;
    }
  }

  /// What comes before a top-level type's body: its annotations, modifiers and name.
  private static void header(String header, Set<String> found) {
    var declaration = DECLARATION.matcher(header);
    if (declaration.find() && GENERATED.matcher(header.substring(0, declaration.start())).find()) {
      found.add(declaration.group(1));
    }
  }

  /// Java source with its comments, strings, text blocks and characters made spaces, lines kept.
  static final class Blanked {
    private Blanked() {}

    static String of(String source) {
      var out = new StringBuilder(source);
      int at = 0;
      while (at < source.length()) {
        at = skip(source, out, at);
      }
      return out.toString();
    }

    /// Blanks what starts at `at`, if it's a comment or a literal, and says where to go on.
    private static int skip(String source, StringBuilder out, int at) {
      if (source.startsWith("//", at)) {
        return blank(out, at, endOf(source, "\n", at + 2, false));
      }
      if (source.startsWith("/*", at)) {
        return blank(out, at, endOf(source, "*/", at + 2, false));
      }
      if (source.startsWith("\"\"\"", at)) {
        return blank(out, at, endOf(source, "\"\"\"", at + 3, true));
      }
      char each = source.charAt(at);
      if (each == '"' || each == '\'') {
        return blank(out, at, endOf(source, String.valueOf(each), at + 1, true));
      }
      return at + 1;
    }

    /// Just past `end` from `from`, or the end of the source; past a backslash's next character
    /// when `escapes`.
    private static int endOf(String source, String end, int from, boolean escapes) {
      int at = from;
      while (at < source.length()) {
        if (escapes && source.charAt(at) == '\\') {
          at += 2;
        } else if (source.startsWith(end, at)) {
          return at + end.length();
        } else {
          at++;
        }
      }
      return source.length();
    }

    private static int blank(StringBuilder out, int from, int to) {
      int end = Math.min(to, out.length());
      for (int at = from; at < end; at++) {
        if (out.charAt(at) != '\n') {
          out.setCharAt(at, ' ');
        }
      }
      return end;
    }
  }
}
