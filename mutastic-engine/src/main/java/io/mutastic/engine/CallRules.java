package io.mutastic.engine;

import io.mutastic.api.Operator.Tier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Rules files: call-site operators as data ([CallRule]). mutastic's own stream rules are one; a
/// plugin's rules file, listed under `plugins:` in `mutastic.yaml`, is another of the same shape:
///
/// ```yaml
/// - id: stream/swap-match
///   explanation: `anyMatch` becomes `allMatch` …
///   owners: java.util.stream.Stream, java.util.stream.IntStream
///   methods: anyMatch, allMatch, noneMatch
///   rewrite: rename anyMatch->allMatch, allMatch->anyMatch, noneMatch->anyMatch
///   changed: changed to {to}
///   headline: no test tells `{name}` from `{to}` in {method}
///   help: use input where some but not all elements match, and assert the result
/// ```
///
/// Read strictly, as exactly this shape: a line it doesn't understand is an error that names it.
public final class CallRules {
  private static final Set<String> KEYS =
      Set.of("id", "explanation", "tier", "owners", "methods", "rewrite", "source", "changed");

  private static final List<String> REQUIRED =
      List.of("id", "explanation", "owners", "methods", "rewrite", "headline", "help");

  /// mutastic's own: the stream pipeline operators.
  static final List<CallRule> BUILT_IN = builtIn("streams.yaml");

  private CallRules() {}

  /// A plugin's rules file.
  public static List<CallRule> read(Path file) {
    try {
      return parse(Files.readString(file), file.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<CallRule> builtIn(String name) {
    try (InputStream in = CallRules.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("mutastic's own rules file " + name + " is missing");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), name);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The rules in `text`: a list of `- key: value` maps, `#` comments, blank lines.
  static List<CallRule> parse(String text, String where) {
    var reader = new Reader(where);
    for (String raw : text.split("\n", -1)) {
      reader.line(raw);
    }
    return reader.rules();
  }

  /// Reads a rules file a line at a time: each `- ` starts a rule, and its fields follow, indented.
  private static final class Reader {
    private final String where;
    private final List<CallRule> rules = new ArrayList<>();
    private @Nullable Map<String, String> current;
    private int number;
    private int start;

    Reader(String where) {
      this.where = where;
    }

    void line(String raw) {
      number++;
      var line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        return;
      }
      if (line.startsWith("- ")) {
        finishRule();
        current = new LinkedHashMap<>();
        start = number;
        field(current, line.substring(2).strip());
      } else if (current != null && raw.startsWith(" ")) {
        field(current, line);
      } else {
        throw error("expected a rule, `- id: …`");
      }
    }

    private void field(Map<String, String> fields, String line) {
      int colon = line.indexOf(':');
      var key = colon < 0 ? "" : line.substring(0, colon);
      if (!known(key)) {
        throw error(
            "expected one of %s, or headline and help"
                .formatted(String.join(", ", KEYS.stream().sorted().toList())));
      }
      if (fields.putIfAbsent(key, unquoted(line.substring(colon + 1).strip())) != null) {
        throw error(key + " is given twice");
      }
    }

    private void finishRule() {
      if (current != null) {
        rules.add(rule(current, where, start));
        current = null;
      }
    }

    List<CallRule> rules() {
      finishRule();
      return List.copyOf(rules);
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException("%s:%d: %s".formatted(where, number, message));
    }
  }

  private static boolean known(String key) {
    return KEYS.contains(key)
        || key.equals("headline")
        || key.equals("help")
        || key.startsWith("headline.")
        || key.startsWith("help.");
  }

  private static CallRule rule(Map<String, String> fields, String where, int line) {
    check(fields, where, line);
    var templates = new LinkedHashMap<String, String>();
    fields.forEach(
        (key, value) -> {
          if (key.startsWith("headline") || key.startsWith("help")) {
            templates.put(key, value);
          }
        });
    var rewrite = required(fields, "rewrite");
    return new CallRule(
        required(fields, "id"),
        required(fields, "explanation"),
        tier(fields.getOrDefault("tier", "defaults"), where, line),
        Set.copyOf(listed(required(fields, "owners"))),
        Set.copyOf(listed(required(fields, "methods"))),
        rewrite,
        fields.getOrDefault("source", ""),
        fields.getOrDefault("changed", "changed"),
        templates);
  }

  /// That the rule has every field it needs, an id of the right shape and a rewrite it can do.
  private static void check(Map<String, String> fields, String where, int line) {
    for (String key : REQUIRED) {
      if (fields.getOrDefault(key, "").isEmpty()) {
        throw new IllegalArgumentException(
            "%s:%d: the rule needs a %s".formatted(where, line, key));
      }
    }
    var id = required(fields, "id");
    if (!id.matches("[a-z0-9-]+/[a-z0-9-]+")) {
      throw new IllegalArgumentException(
          "%s:%d: an id is `namespace/name`, lower case: %s".formatted(where, line, id));
    }
    checkRewrite(required(fields, "rewrite"), where, line);
  }

  /// A field [#check] has already made sure of.
  private static String required(Map<String, String> fields, String key) {
    return fields.getOrDefault(key, "");
  }

  private static void checkRewrite(String rewrite, String where, int line) {
    boolean renames =
        rewrite.startsWith("rename ")
            && listed(rewrite.substring("rename ".length())).stream()
                .allMatch(pair -> pair.matches("\\w+->\\w+"));
    boolean call = rewrite.matches("call \\w+\\([^)]*\\)( with .+)?");
    if (!rewrite.equals("remove") && !renames && !call) {
      throw new IllegalArgumentException(
          "%s:%d: a rewrite is `remove`, `rename a->b, …` or `call name(ARGS) with A, B`, not %s"
              .formatted(where, line, rewrite));
    }
  }

  private static Tier tier(String word, String where, int line) {
    try {
      return Tier.valueOf(word.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "%s:%d: a tier is defaults or stronger, not %s".formatted(where, line, word), e);
    }
  }

  private static List<String> listed(String list) {
    return Arrays.stream(list.split(","))
        .map(String::strip)
        .filter(each -> !each.isEmpty())
        .toList();
  }

  private static String unquoted(String value) {
    return value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))
        ? value.substring(1, value.length() - 1)
        : value;
  }
}
