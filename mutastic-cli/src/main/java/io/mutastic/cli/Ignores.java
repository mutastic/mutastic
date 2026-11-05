package io.mutastic.cli;

import io.mutastic.api.Ignore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// `.mutastic/ignores.yaml`: the mutants a project has decided not to test, written by `mutastic
/// ignore` and committed, so each decision is a diff someone reviews. Machine-written, apart from
/// `mutastic.yaml`, because it churns.
///
/// ```yaml
/// ignores:
///   - id: m855c8e3c
///     at: com.example.pricing.Pricing#discount, line 17
///     change: '`>=` becomes `>`'
///     code: 'if (total >= 100) {'
///     reason: 'totals are rounded to tens here'
///     kind: unreachable
/// ```
///
/// `at` and `change` are for the reader; `code` is the line the decision was made on.
final class Ignores {
  static final Path FILE = Path.of(".mutastic", "ignores.yaml");

  /// Ignores proposed, by a coding agent mostly, waiting for a person: `mutastic ignore --propose`
  /// writes them, `--accept` moves one into [#FILE]. A proposed mutant still counts as missed.
  static final Path PROPOSALS = Path.of(".mutastic", "proposals.yaml");

  private static final List<String> HEADER =
      List.of(
          "# Mutants this project has decided not to test, written by `mutastic ignore`. Commit it:",
          "# each entry is a decision to review. One whose code has changed no longer holds; remove",
          "# those with `mutastic ignore --prune`.",
          "ignores:");

  private static final List<String> PROPOSALS_HEADER =
      List.of(
          "# Ignores proposed and waiting for a person, written by `mutastic ignore --propose`: until",
          "# one is accepted (`mutastic ignore --accept ID`), its mutant still counts as missed.",
          "proposals:");

  /// One decision, with what the reader needs to review it.
  record Entry(Ignore ignore, String at, String change) {}

  private Ignores() {}

  static List<Entry> read(Path directory) {
    return read(directory, FILE);
  }

  /// The proposed ignores, waiting for a person.
  static List<Entry> proposed(Path directory) {
    return read(directory, PROPOSALS);
  }

  private static List<Entry> read(Path directory, Path name) {
    var file = directory.resolve(name);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try {
      return parse(Files.readAllLines(file), name);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void write(Path directory, List<Entry> entries) {
    write(directory, entries, FILE, HEADER);
  }

  static void propose(Path directory, List<Entry> entries) {
    write(directory, entries, PROPOSALS, PROPOSALS_HEADER);
  }

  private static void write(Path directory, List<Entry> entries, Path name, List<String> header) {
    if (name.equals(PROPOSALS) && entries.isEmpty()) {
      delete(directory.resolve(name)); // none waiting: nothing to commit
      return;
    }
    var lines = new ArrayList<>(header);
    for (Entry entry : entries) {
      lines.add("  - id: " + entry.ignore().id());
      lines.add("    at: " + quoted(entry.at()));
      lines.add("    change: " + quoted(entry.change()));
      lines.add("    code: " + quoted(entry.ignore().code()));
      lines.add("    reason: " + quoted(entry.ignore().reason()));
      if (!entry.ignore().kind().isEmpty()) {
        lines.add("    kind: " + entry.ignore().kind());
      }
    }
    try {
      var file = directory.resolve(name);
      Files.createDirectories(file.getParent());
      Files.write(file, lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// Entries in the shape written: an `- id:` line, then its fields, one per line.
  private static List<Entry> parse(List<String> lines, Path name) {
    var entries = new ArrayList<Entry>();
    Map<String, String> fields = new LinkedHashMap<>();
    for (String raw : lines) {
      var line = raw.strip();
      if (line.isEmpty()
          || line.startsWith("#")
          || line.equals("ignores:")
          || line.equals("proposals:")) {
        continue;
      }
      if (line.startsWith("- ")) {
        add(entries, fields, name);
        fields = new LinkedHashMap<>();
        line = line.substring(2);
      }
      int colon = line.indexOf(':');
      if (colon < 0) {
        throw new Main.UsageException("%s: can't read the line `%s`".formatted(name, raw));
      }
      fields.put(line.substring(0, colon), unquoted(line.substring(colon + 1).strip()));
    }
    add(entries, fields, name);
    return entries;
  }

  private static void add(List<Entry> entries, Map<String, String> fields, Path name) {
    if (fields.isEmpty()) {
      return;
    }
    var id = fields.get("id");
    var reason = fields.get("reason");
    if (id == null || reason == null) {
      throw new Main.UsageException("%s: every entry needs an id and a reason".formatted(name));
    }
    entries.add(
        new Entry(
            new Ignore(
                id, fields.getOrDefault("code", ""), reason, fields.getOrDefault("kind", "")),
            fields.getOrDefault("at", ""),
            fields.getOrDefault("change", "")));
  }

  /// In single quotes, as YAML has them: a quote inside is doubled.
  private static String quoted(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String unquoted(String value) {
    return value.length() >= 2 && value.startsWith("'") && value.endsWith("'")
        ? value.substring(1, value.length() - 1).replace("''", "'")
        : value;
  }
}
