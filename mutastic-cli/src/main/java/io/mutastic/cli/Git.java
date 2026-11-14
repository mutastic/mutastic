package io.mutastic.cli;

import io.mutastic.api.ChangedLines;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/// The Java lines a change touched, from git: since the merge-base of `HEAD` and a commit, so
/// `--since=main` on a branch is the branch's own change, uncommitted edits and new files included.
/// Lines a change only deleted aren't there: nothing is left on them to mutate.
final class Git {
  /// `@@ -12,3 +14,5 @@`: five lines from line 14 of the new file.
  private static final Pattern HUNK =
      Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(?<start>\\d+)(?:,(?<count>\\d+))? @@");

  /// The working tree against a commit, inserted before `--`: changed lines only, Java only.
  private static final List<String> DIFF =
      List.of("diff", "--unified=0", "--no-color", "--no-ext-diff", "--no-renames", "--", "*.java");

  private final Path directory;

  Git(Path directory) {
    this.directory = directory;
  }

  /// The commit checked out: where a gated task starts.
  String head() {
    return output(List.of("rev-parse", "HEAD"), "the gate needs a git repository with a commit")
        .strip();
  }

  /// The working tree as git would commit it, untracked files included and ignored ones not: the
  /// same hash for the same files. A scratch index takes the files, so the real one is untouched.
  String tree() {
    try {
      var index = Files.createTempFile("mutastic-gate", ".index");
      try {
        var real =
            Path.of(output(List.of("rev-parse", "--git-path", "index"), "no git index").strip());
        Files.copy(
            directory.resolve(real), index, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var scratch = Map.of("GIT_INDEX_FILE", index.toString());
        if (run(List.of("add", "--all"), scratch).isEmpty()) {
          throw new Main.ConfigException("git", "git couldn't read the working tree");
        }
        return run(List.of("write-tree"), scratch).orElse("").strip();
      } finally {
        Files.deleteIfExists(index);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// Whether the working tree is the commit `base`'s, file for file: a task that changed nothing
  /// can't have broken a test.
  boolean untouchedSince(String base) {
    return run(List.of("rev-parse", base + "^{tree}"))
        .map(String::strip)
        .filter(tree()::equals)
        .isPresent();
  }

  /// A file as it was at a commit, by its path from this directory: none if it wasn't there, or
  /// this directory isn't.
  Optional<String> at(String commit, Path file) {
    return run(List.of("show", commit + ":./" + file.toString().replace('\\', '/')));
  }

  /// The diff of everything under this directory, from a commit to the working tree: no context,
  /// and empty if git can't make it.
  String diff(String commit) {
    return run(List.of(
            "diff",
            "--unified=0",
            "--no-color",
            "--no-ext-diff",
            "--no-renames",
            commit,
            "--",
            "."))
        .orElse("");
  }

  /// The lines changed since `since`, or since the repository's default branch for `""`.
  ChangedLines changedSince(String since) {
    var root =
        Path.of(
            output(List.of("rev-parse", "--show-toplevel"), "--since needs a git repository")
                .strip());
    var reference = since.isEmpty() ? defaultBranch() : since;
    var base =
        output(List.of("merge-base", reference, "HEAD"), "git knows no commit " + reference)
            .strip();
    var lines = new HashMap<Path, Set<Integer>>();
    diff(root, base, lines);
    untracked(root, lines);
    return new ChangedLines(reference, lines);
  }

  /// Each Java file's added lines in the diff from `base` to the working tree.
  private void diff(Path root, String base, Map<Path, Set<Integer>> lines) {
    var args = new ArrayList<>(DIFF);
    args.add(args.size() - 2, base);
    var diff = output(args, "git couldn't diff");
    Set<Integer> changed = new TreeSet<>(); // the current file's, until a file starts
    for (String line : diff.lines().toList()) {
      if (line.startsWith("+++ ")) {
        var name = line.substring(4);
        changed = new TreeSet<>();
        if (!name.equals("/dev/null")) {
          lines.put(root.resolve(name.substring(2)).normalize(), changed);
        }
      } else {
        added(line, changed);
      }
    }
    lines.values().removeIf(Set::isEmpty);
  }

  /// The lines a hunk header says it added: `@@ -12,3 +14,5 @@` adds 14 to 18.
  private static void added(String line, Set<Integer> changed) {
    var hunk = HUNK.matcher(line);
    if (hunk.find()) {
      int start = Integer.parseInt(hunk.group("start"));
      int count = hunk.group("count") == null ? 1 : Integer.parseInt(hunk.group("count"));
      for (int number = start; number < start + count; number++) {
        changed.add(number);
      }
    }
  }

  /// New Java files git doesn't track yet: every line of them is the change.
  private void untracked(Path root, Map<Path, Set<Integer>> lines) {
    for (String name :
        output(
                List.of(
                    "ls-files", "--others", "--exclude-standard", "--full-name", "--", "*.java"),
                "git couldn't list new files")
            .lines()
            .filter(name -> !name.isBlank())
            .toList()) {
      var file = root.resolve(name).normalize();
      try {
        var count = Files.readAllLines(file, StandardCharsets.UTF_8).size();
        var changed = new TreeSet<Integer>();
        for (int number = 1; number <= count; number++) {
          changed.add(number);
        }
        lines.put(file, changed);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /// `origin/HEAD`, as the remote names its default branch, else `main`, else `master`.
  private String defaultBranch() {
    for (String candidate : List.of("origin/HEAD", "main", "master")) {
      if (succeeds(List.of("rev-parse", "--verify", "--quiet", candidate))) {
        return candidate;
      }
    }
    throw new Main.ConfigException(
        "git",
        "--since without a commit means the default branch, and this repository has no origin/HEAD,"
            + " main or master: name one, as --since=develop");
  }

  private boolean succeeds(List<String> args) {
    return run(args).isPresent();
  }

  private String output(List<String> args, String failure) {
    return run(args)
        .orElseThrow(() -> new Main.ConfigException("git", failure + " (in " + directory + ")"));
  }

  /// What git printed, or none if it failed (or isn't there).
  private Optional<String> run(List<String> args) {
    return run(args, Map.of());
  }

  private Optional<String> run(List<String> args, Map<String, String> environment) {
    var command = new ArrayList<>(List.of("git", "-C", directory.toString()));
    command.addAll(args);
    try {
      var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
      builder.environment().putAll(environment);
      var process = builder.start();
      var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return process.waitFor() == 0 ? Optional.of(out) : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
