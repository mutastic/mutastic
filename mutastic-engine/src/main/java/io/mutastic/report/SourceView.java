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
