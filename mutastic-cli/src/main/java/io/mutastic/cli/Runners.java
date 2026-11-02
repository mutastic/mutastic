package io.mutastic.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// A `mutastic` command as each build plugin runs it, for the docs: `mutastic run . :core
/// --threshold=70` is `./gradlew :core:mutasticRun -Pmutastic.threshold=70` and `mvn mutastic:run
/// -Dmutastic.threshold=70 -pl core`. Each option becomes its `mutastic.` property, read from
/// [Options], so the forms can't name a property the plugins don't pass on.
final class Runners {
  private Runners() {}

  /// What a list setting's items are called in a POM: `<reports><report>xml</report></reports>`.
  private static final Map<String, String> ITEMS =
      Map.of("testsFrom", "module", "reports", "report");

  /// `mutastic run --threshold=70`, read: its command, the options with their values, the mutant's
  /// id, the directory and the module.
  private record Read(
      String command, List<Setting> settings, String id, String directory, String module) {}

  /// One option as given, and its value: empty for a flag or a bare `--since`.
  record Setting(Option option, String value) {
    /// `threshold=70`, `confirmCatches`, `suggestions=false`: as a plugin property, after
    /// `mutastic.`.
    String property() {
      var name = option.property();
      if (option.negated()) {
        return name + "=false";
      }
      return option.kind() == Option.Kind.FLAG ? name : name + "=" + value;
    }
  }

  static String gradle(String commandLine) {
    var read = read(commandLine);
    var task =
        "mutastic"
            + read.command.substring(0, 1).toUpperCase(Locale.ROOT)
            + read.command.substring(1);
    var words =
        new ArrayList<>(
            List.of("./gradlew", read.module + (read.module.isEmpty() ? "" : ":") + task));
    if (!read.id.isEmpty()) {
      words.add("-Pmutastic.mutant=" + read.id);
    }
    read.settings.forEach(setting -> words.add("-Pmutastic." + setting.property()));
    if (!read.directory.isEmpty()) {
      words.addAll(List.of("-p", read.directory));
    }
    return String.join(" ", words);
  }

  static String maven(String commandLine) {
    var read = read(commandLine);
    var words = new ArrayList<>(List.of("mvn", "mutastic:" + read.command));
    if (!read.id.isEmpty()) {
      words.add("-Dmutastic.mutant=" + read.id);
    }
    read.settings.forEach(setting -> words.add("-Dmutastic." + setting.property()));
    if (!read.directory.isEmpty()) {
      words.addAll(List.of("-f", read.directory));
    }
    if (!read.module.isEmpty()) {
      words.addAll(List.of("-pl", read.module.substring(1)));
    }
    return String.join(" ", words);
  }

  /// The option's setting in `build.gradle.kts`, as its example sets it: `mutastic { threshold = 70
  /// }`. Empty when it isn't a build-file setting.
  static String gradleBuild(Option option) {
    var setting = setting(option);
    if (setting == null) {
      return "";
    }
    var value =
        option.kind() == Option.Kind.REPEATED
            ? "listOf(\"" + setting.value() + "\")"
            : option.kind() == Option.Kind.FLAG
                ? String.valueOf(!option.negated())
                : setting.value().matches("-?\\d+")
                    ? setting.value()
                    : "\"" + setting.value() + "\"";
    return "mutastic {\n    %s = %s\n}".formatted(option.property(), value);
  }

  /// The option's setting in the plugin's `<configuration>`, as its example sets it. Empty when it
  /// isn't a build-file setting.
  static String mavenBuild(Option option) {
    var setting = setting(option);
    if (setting == null) {
      return "";
    }
    var name = option.property();
    var value =
        option.kind() == Option.Kind.FLAG ? String.valueOf(!option.negated()) : setting.value();
    var element =
        option.kind() == Option.Kind.REPEATED
            ? "<%s>\n    <%s>%s</%s>\n  </%s>"
                .formatted(name, ITEMS.get(name), value, ITEMS.get(name), name)
            : "<%s>%s</%s>".formatted(name, value, name);
    return "<configuration>\n  %s\n</configuration>".formatted(element);
  }

  /// The setting the option's example gives it, or null when the plugins take it on the command
  /// line only.
  private static @Nullable Setting setting(Option option) {
    if (!option.buildSetting()) {
      return null;
    }
    return read(option.example()).settings.stream()
        .filter(setting -> setting.option().equals(option))
        .findFirst()
        .orElse(null);
  }

  private static Read read(String commandLine) {
    var words = words(commandLine);
    var command = words.get(1);
    var settings = new ArrayList<Setting>();
    var id = "";
    var directory = "";
    var module = "";
    boolean takesId = Main.Subcommand.of(command).map(each -> each.takesMutantId).orElse(false);
    for (String word : words.subList(2, words.size())) {
      if (word.startsWith("--")) {
        int equals = word.indexOf('=');
        var option = Options.named(equals < 0 ? word : word.substring(0, equals));
        settings.add(new Setting(option, equals < 0 ? "" : word.substring(equals + 1)));
      } else if (word.startsWith(":")) {
        module = word;
      } else if (takesId && id.isEmpty()) {
        id = word;
      } else if (!word.equals(".")) {
        directory = word;
      }
    }
    return new Read(command, settings, id, directory, module);
  }

  /// `line` split as a shell would: on spaces, but not inside double quotes, which stay.
  private static List<String> words(String line) {
    var words = new ArrayList<String>();
    var word = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      }
      if (character == ' ' && !quoted) {
        if (word.length() > 0) {
          words.add(word.toString());
          word.setLength(0);
        }
      } else {
        word.append(character);
      }
    }
    if (word.length() > 0) {
      words.add(word.toString());
    }
    return words;
  }
}
