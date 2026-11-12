package io.mutastic.cli;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Filter;
import io.mutastic.api.Mutator;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import io.mutastic.engine.CallRules;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.ServiceLoader;
import org.jspecify.annotations.Nullable;

/// `mutastic.yaml`, in the project's directory: the project's own say over which mutants run.
///
/// ```yaml
/// extends: ../mutastic.yaml
/// rules:
///   - skip: com.acme.generated.**
///     reason: protobuf output
///   - mutate: com.acme.Money#toString
///     reason: shown to customers
/// plugins:
///   - build/libs/acme-filters.jar
///   - mutiny.yaml
/// filters:
///   - acme:no-metrics
/// operators:
///   - stronger
///   - -stream/swap-match
/// ```
///
/// Read strictly, as exactly this shape: a line it doesn't understand is an error that names it,
/// never a guess. A plugin is a jar, whose filters run only when `filters:` names them and whose
/// operators run as `operators:` chooses, or a rules file (`.yaml`) of call operators. `operators:`
/// chooses as `--operators` does, which replaces it.
///
/// A module uses the nearest `mutastic.yaml` at or above its directory, however it's run, so the
/// same module gets the same rules from the build's top as from its own directory. A file adds
/// another's only when it says so, with `extends:`: one file and the ones it names are all that
/// apply, never a cascade nobody wrote down.
record Configuration(
    List<Rule> rules, List<Path> plugins, List<String> filters, List<String> operators) {
  static final String FILE = "mutastic.yaml";
  static final Configuration NONE = new Configuration(List.of(), List.of(), List.of(), List.of());

  Configuration {
    rules = List.copyOf(rules);
    plugins = List.copyOf(plugins);
    filters = List.copyOf(filters);
    operators = List.copyOf(operators);
  }

  /// The project's configuration, or none when it has no `mutastic.yaml`: the file's own, then what
  /// it `extends:`.
  static Configuration read(Path directory) {
    var file = directory.resolve(FILE).toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      return NONE;
    }
    var chain = new LinkedHashSet<Path>();
    chain.add(file);
    return read(file, chain);
  }

  /// Where a module's `mutastic.yaml` is: the nearest at or above the module's directory, no higher
  /// than the top of its repository (or than the run's directory, outside one). Else the run's
  /// directory, which may have none.
  static Path nearest(Path module, Path run) {
    var from = module.toAbsolutePath().normalize();
    var top = repository(from).orElse(run.toAbsolutePath().normalize());
    for (Path at = from; at != null && at.startsWith(top); at = at.getParent()) {
      if (Files.isRegularFile(at.resolve(FILE))) {
        return at;
      }
    }
    return run;
  }

  /// The top of the git repository `directory` is in, if it's in one.
  private static Optional<Path> repository(Path directory) {
    for (Path at = directory; at != null; at = at.getParent()) {
      if (Files.exists(at.resolve(".git"))) {
        return Optional.of(at);
      }
    }
    return Optional.empty();
  }

  /// One file, then the files its `extends:` names in turn; `chain` is those read so far, so a file
  /// that comes back round is an error rather than a loop.
  private static Configuration read(Path file, SequencedSet<Path> chain) {
    var reader = new Reader(file, shown(chain, file));
    Configuration own;
    try {
      own = reader.read();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var extended = reader.extended;
    if (extended == null) {
      return own;
    }
    if (!chain.add(extended)) {
      var names = new ArrayList<String>();
      chain.forEach(each -> names.add(shown(chain, each).toString()));
      names.add(shown(chain, extended).toString());
      throw new Main.UsageException(
          "extends: goes round in a loop: " + String.join(" extends ", names));
    }
    return own.over(read(extended, chain).movedTo(directoryOf(extended), directoryOf(file)));
  }

  /// A file as its errors name it: from the directory of the first file read.
  private static Path shown(SequencedSet<Path> chain, Path file) {
    return directoryOf(chain.getFirst()).relativize(file);
  }

  /// The directory a file read by its absolute path is in.
  private static Path directoryOf(Path file) {
    return java.util.Objects.requireNonNull(file.getParent(), file::toString);
  }

  /// This file's say over `parent`'s: its rules first, so a module's own reason is the one shown,
  /// then the parent's; the plugins and filters of both; and its operators, if it chooses any, in
  /// place of the parent's, as `--operators` replaces them.
  private Configuration over(Configuration parent) {
    return new Configuration(
        joined(rules, parent.rules),
        joined(plugins, parent.plugins),
        joined(filters, parent.filters),
        operators.isEmpty() ? parent.operators : operators);
  }

  /// `first` then `then`, each once.
  private static <T> List<T> joined(List<T> first, List<T> then) {
    var both = new LinkedHashSet<T>(first);
    both.addAll(then);
    return List.copyOf(both);
  }

  /// The same configuration, its plugins' paths from `to` rather than `from`, the directory another
  /// file read it from.
  private Configuration movedTo(Path from, Path to) {
    var moved = plugins.stream().map(plugin -> to.relativize(from.resolve(plugin))).toList();
    return new Configuration(rules, moved, filters, operators);
  }

  /// The rules, the filters `filters:` names, found in the plugins, and the operators the plugins
  /// add: a jar's, then each rules file's.
  Policy policy(Path directory) {
    var jars = plugins(directory);
    var mutators = new ArrayList<Mutator>();
    ServiceLoader.load(Mutator.class, jars).forEach(mutators::add);
    for (Path plugin : plugins) {
      if (isRules(plugin)) {
        try {
          mutators.addAll(CallRules.read(directory.resolve(plugin)));
        } catch (IllegalArgumentException e) {
          throw new Main.UsageException(String.valueOf(e.getMessage()));
        }
      }
    }
    var classMutators = new ArrayList<ClassMutator>();
    ServiceLoader.load(ClassMutator.class, jars).forEach(classMutators::add);
    return new Policy(rules, filters(jars), List.of())
        .withOperators(operators, mutators)
        .withClassMutators(classMutators);
  }

  /// The filters `filters:` names, from the plugin jars.
  private List<Filter> filters(ClassLoader jars) {
    if (filters.isEmpty()) {
      return List.of();
    }
    var found = new LinkedHashMap<String, Filter>();
    ServiceLoader.load(Filter.class, jars)
        .forEach(filter -> found.putIfAbsent(filter.id(), filter));
    var named = new ArrayList<Filter>();
    for (String id : filters) {
      var filter = found.get(id);
      if (filter == null) {
        throw new Main.UsageException(
            "%s names the filter %s, which no plugin has%s"
                .formatted(
                    FILE,
                    id,
                    found.isEmpty()
                        ? ""
                        : " (they have " + String.join(", ", found.keySet()) + ")"));
      }
      named.add(filter);
    }
    return named;
  }

  /// A rules file rather than a jar.
  private static boolean isRules(Path plugin) {
    var name = plugin.getFileName().toString();
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }

  /// The plugin jars, to load services from.
  private ClassLoader plugins(Path directory) {
    var urls = new ArrayList<URL>();
    for (Path plugin : plugins) {
      var jar = directory.resolve(plugin);
      if (!Files.isRegularFile(jar)) {
        throw new Main.UsageException(
            "%s names the plugin %s, which isn't there".formatted(FILE, plugin));
      }
      if (isRules(plugin)) {
        continue;
      }
      try {
        urls.add(jar.toUri().toURL());
      } catch (MalformedURLException e) {
        throw new IllegalStateException(e);
      }
    }
    return new URLClassLoader(urls.toArray(URL[]::new), Configuration.class.getClassLoader());
  }

  /// Reads the file a line at a time, in the shape above.
  private static final class Reader {
    private final Path file;
    private final Path shown;
    private final List<Rule> rules = new ArrayList<>();
    private final List<Path> plugins = new ArrayList<>();
    private final List<String> filters = new ArrayList<>();
    private final List<String> operators = new ArrayList<>();
    private @Nullable Path extended;
    private String section = "";
    private @Nullable String pendingVerb;
    private @Nullable String pendingTarget;
    private int number;

    Reader(Path file, Path shown) {
      this.file = file;
      this.shown = shown;
    }

    Configuration read() throws IOException {
      for (String raw : Files.readAllLines(file)) {
        number++;
        var line = withoutComment(raw);
        if (!line.isBlank()) {
          read(line);
        }
      }
      requireReason();
      return new Configuration(rules, plugins, filters, operators);
    }

    private void read(String line) {
      if (!line.startsWith(" ")) {
        requireReason();
        section = line.strip();
        if (section.startsWith("extends:")) {
          extend(value(section.substring("extends:".length())));
        } else if (!List.of("rules:", "plugins:", "filters:", "operators:").contains(section)) {
          throw error("expected extends:, rules:, plugins:, filters: or operators:");
        }
        return;
      }
      var item = line.strip();
      switch (section) {
        case "rules:" -> rule(item);
        case "plugins:" -> plugins.add(Path.of(listed(item)));
        case "filters:" -> filters.add(listed(item));
        case "operators:" -> operators.add(listed(item));
        default -> throw error("a list item needs a section above it");
      }
    }

    /// `extends: <path>`, from this file's directory, to a file that's there; once.
    private void extend(String path) {
      if (extended != null) {
        throw error("a file extends one other file, once");
      }
      if (path.isEmpty()) {
        throw error("`extends:` needs a path, such as ../mutastic.yaml");
      }
      var named = file.resolveSibling(path).normalize();
      if (!Files.isRegularFile(named)) {
        throw error("extends %s, which isn't there".formatted(path));
      }
      extended = named;
    }

    private void rule(String item) {
      if (item.startsWith("reason:") && pendingVerb != null) {
        rules.add(
            new Rule(
                pendingVerb.equals("mutate"),
                java.util.Objects.requireNonNull(pendingTarget),
                value(item.substring("reason:".length()))));
        pendingVerb = null;
        return;
      }
      var listed = listed(item);
      int colon = listed.indexOf(':');
      var verb = colon < 0 ? "" : listed.substring(0, colon);
      if (!verb.equals("skip") && !verb.equals("mutate")) {
        throw error("a rule is `- skip: <pattern>` or `- mutate: <pattern>`, then `reason: …`");
      }
      requireReason();
      pendingVerb = verb;
      pendingTarget = value(listed.substring(colon + 1));
    }

    /// Every skip says why: a rule without a reason is an error, not "no reason given".
    private void requireReason() {
      if (pendingVerb != null) {
        throw error(
            "the rule `%s: %s` needs a `reason:` under it".formatted(pendingVerb, pendingTarget));
      }
    }

    private String listed(String item) {
      if (!item.startsWith("- ")) {
        throw error("expected a list item, `- …`");
      }
      return value(item.substring(2));
    }

    private static String value(String text) {
      var value = text.strip();
      return value.length() >= 2 && (quotedBy(value, '"') || quotedBy(value, '\''))
          ? value.substring(1, value.length() - 1)
          : value;
    }

    private static boolean quotedBy(String value, char quote) {
      return value.charAt(0) == quote && value.charAt(value.length() - 1) == quote;
    }

    /// A `#` at the start or after a space starts a comment, as in YAML.
    private static String withoutComment(String line) {
      for (int at = 0; at < line.length(); at++) {
        if (line.charAt(at) == '#' && (at == 0 || line.charAt(at - 1) == ' ')) {
          return line.substring(0, at);
        }
      }
      return line;
    }

    private Main.UsageException error(String what) {
      return new Main.UsageException("%s line %d: %s".formatted(shown, number, what));
    }
  }
}
