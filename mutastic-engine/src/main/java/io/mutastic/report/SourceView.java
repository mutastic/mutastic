package io.mutastic.report;

import io.mutastic.engine.Mutant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A mutant as the user wrote it: its source file and line, and the operator it changes.
///
/// javac compiles an `if` to a jump past its body, so the jump tests the opposite of the
/// source: `if (total >= 100)` becomes `IF_ICMPLT`, "jump if less". The source operator is the
/// negation of the jump's. Loops and `?:` compile the same way; a `do … while` doesn't, and its
/// mutated line is then left out rather than shown wrong.
public final class SourceView {
  private static final Map<String, String> JUMP_TO_SOURCE = Map.of(
      "LT", ">=", "LE", ">", "GT", "<=", "GE", "<");

  private final Path root;
  private final List<Path> sources;

  /// `root` is the project directory, which locations are relative to.
  public SourceView(Path root, List<Path> sources) {
    this.root = root;
    this.sources = List.copyOf(sources);
  }

  /// `src/main/java/com/example/Pricing.java`, relative to the project, or the class's own
  /// path if no source directory has it.
  public String file(Mutant mutant) {
    return sourceFile(mutant).map(this::relative).orElse(expectedPath(mutant));
  }

  /// The line's text, stripped, if the source is there.
  public Optional<String> line(Mutant mutant) {
    return sourceFile(mutant).flatMap(file -> {
      try {
        var lines = Files.readAllLines(file);
        return mutant.line() >= 1 && mutant.line() <= lines.size()
            ? Optional.of(lines.get(mutant.line() - 1).strip())
            : Optional.empty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /// The source operator before the change: `>=` for `IF_ICMPLT`.
  public static String originalOperator(Mutant mutant) {
    return JUMP_TO_SOURCE.get(condition(mutant.original()));
  }

  /// The source operator after the change: `>` for `IF_ICMPLE`.
  public static String mutatedOperator(Mutant mutant) {
    return JUMP_TO_SOURCE.get(condition(mutant.replacement()));
  }

  /// The line with the change made, when the operator appears on it exactly once.
  public Optional<String> mutatedLine(Mutant mutant) {
    var before = " " + originalOperator(mutant) + " ";
    return line(mutant)
        .filter(text -> text.indexOf(before) >= 0 && text.indexOf(before) == text.lastIndexOf(before))
        .map(text -> text.replace(before, " " + mutatedOperator(mutant) + " "));
  }

  private static String condition(String opcode) {
    return opcode.substring(opcode.length() - 2);
  }

  private Optional<Path> sourceFile(Mutant mutant) {
    return sources.stream().map(source -> source.resolve(expectedPath(mutant))).filter(Files::isRegularFile).findFirst();
  }

  /// `com/example/Pricing.java`, for `com/example/Pricing$Tier` too.
  private static String expectedPath(Mutant mutant) {
    var name = mutant.className();
    int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
    return (dollar < 0 ? name : name.substring(0, dollar)) + ".java";
  }

  private String relative(Path file) {
    var absolute = file.toAbsolutePath().normalize();
    var base = root.toAbsolutePath().normalize();
    return absolute.startsWith(base) ? base.relativize(absolute).toString() : absolute.toString();
  }
}
