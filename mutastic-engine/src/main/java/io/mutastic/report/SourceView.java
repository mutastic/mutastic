package io.mutastic.report;

import io.mutastic.engine.Mutant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// A mutant as the user wrote it: its source file and line, and the line as the mutant has it.
public final class SourceView {
  private final Path root;
  private final List<Path> sources;
  private final List<Path> testSources;

  /// `root` is the project directory, which locations are relative to.
  public SourceView(Path root, List<Path> sources) {
    this(root, sources, List.of());
  }

  /// With the test sources too, so suggested tests match the class they'd go into.
  public SourceView(Path root, List<Path> sources, List<Path> testSources) {
    this.root = root;
    this.sources = List.copyOf(sources);
    this.testSources = List.copyOf(testSources);
  }

  /// A test class's source file, relative to the project, if it's in the test sources.
  public Optional<String> testFile(String testClass) {
    var relative = testClass.replace('.', '/') + ".java";
    return testSources.stream().map(source -> source.resolve(relative)).filter(Files::isRegularFile).findFirst()
        .map(this::relative);
  }

  /// A test class's source, `com.example.PricingTest`, if it's in the test sources.
  public Optional<String> testSource(String testClass) {
    var relative = testClass.replace('.', '/') + ".java";
    return testSources.stream().map(source -> source.resolve(relative)).filter(Files::isRegularFile).findFirst().map(file -> {
      try {
        return Files.readString(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /// `src/main/java/com/example/Pricing.java`, relative to the project, or the class's own
  /// path if no source directory has it.
  public String file(Mutant mutant) {
    return sourceFile(mutant).map(this::relative).orElse(expectedPath(mutant));
  }

  /// The source file itself, for linking to, if it's there.
  public Optional<Path> path(Mutant mutant) {
    return sourceFile(mutant).map(SourceView::real);
  }

  /// The source file's text exactly as on disk, if it's there.
  public Optional<String> text(Mutant mutant) {
    return sourceFile(mutant).map(file -> {
      try {
        return Files.readString(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /// The source file's lines as written, if it's there.
  public Optional<List<String>> lines(Mutant mutant) {
    return sourceFile(mutant).map(file -> {
      try {
        return Files.readAllLines(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
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

  /// Where on its line the change is, as 1-based columns, end exclusive: the operator when the
  /// line has it once, the method name for a removed call, the `return` statement for a
  /// returned default, else the whole statement. Columns 1 to 2 when the source isn't there.
  public int[] columns(Mutant mutant, Advice advice) {
    var text = lines(mutant).filter(all -> mutant.line() >= 1 && mutant.line() <= all.size())
        .map(all -> all.get(mutant.line() - 1));
    if (text.isEmpty() || text.get().isBlank()) {
      return new int[] {1, 2};
    }
    var line = text.get();
    var token = advice.before().map(before -> " " + before + " ")
        .or(() -> Optional.ofNullable(switch (mutant.operator()) {
          case REMOVE_VOID_CALL -> calledName(mutant.original()) + "(";
          case RETURN_DEFAULT -> "return ";
          default -> null;
        }));
    if (token.isPresent()) {
      int at = line.indexOf(token.get());
      if (at >= 0 && at == line.lastIndexOf(token.get())) {
        return switch (mutant.operator()) {
          case REMOVE_VOID_CALL -> new int[] {at + 1, at + token.get().length()};
          case RETURN_DEFAULT -> new int[] {at + 1, line.stripTrailing().length() + 1};
          default -> new int[] {at + 2, at + token.get().length()}; // inside the spaces around it
        };
      }
    }
    int indent = line.length() - line.stripLeading().length();
    return new int[] {indent + 1, line.stripTrailing().length() + 1};
  }

  /// `record` for `INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V`.
  static String calledName(String call) {
    var target = call.substring(call.indexOf(' ') + 1, call.indexOf('('));
    return target.substring(target.lastIndexOf('.') + 1);
  }

  /// The line with the change made, when it can be shown exactly: the changed operator
  /// appears on the line once, or the line is a single `return`.
  public Optional<String> mutatedLine(Mutant mutant, Advice advice) {
    return line(mutant).flatMap(text -> {
      if (advice.before().isPresent()) {
        var before = " " + advice.before().get() + " ";
        int at = text.indexOf(before);
        return at >= 0 && at == text.lastIndexOf(before)
            ? Optional.of(text.replace(before, " " + advice.after().orElseThrow() + " "))
            : Optional.empty();
      }
      boolean singleReturn = text.startsWith("return ") && text.endsWith(";") && text.indexOf("return ", 1) < 0;
      return advice.after().filter(after -> singleReturn && after.startsWith("return "));
    });
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
    var absolute = real(file);
    var base = real(root);
    return absolute.startsWith(base) ? base.relativize(absolute).toString() : absolute.toString();
  }

  /// Links resolved: the build may name a directory through one (macOS's `/var` is
  /// `/private/var`) and the user through the other.
  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }
}
