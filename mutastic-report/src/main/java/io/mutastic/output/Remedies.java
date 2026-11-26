package io.mutastic.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// The commands an error ends in, as the errors standard has them: each on a line of its own, set
/// in by two spaces, with how sure the tool is of each, as rustc grades a suggestion. `exact` runs
/// as it is; `placeholders` has a part in `<…>` to fill in first; `maybe` is a close name, a guess
/// at what was meant, to check before running it. A close name is offered in the message as
/// `There's one called --format.`, never acted on.
public final class Remedies {
  private Remedies() {}

  /// A close name the message offers: `There's one called --format.`
  private static final Pattern OFFERED =
      Pattern.compile("There's one called (?<name>\\S+?)[.,]?(?=\\s|$)");

  /// The commands in `said`, each line `command` finds one at the start of, with how sure of each:
  /// `applicability`, then `command`.
  public static List<Map<String, Object>> in(String said, Pattern command) {
    var offered = new ArrayList<String>();
    var names = OFFERED.matcher(said);
    while (names.find()) {
      offered.add(names.group("name").replaceFirst(":$", ""));
    }
    var commands = new ArrayList<Map<String, Object>>();
    for (String line : said.lines().toList()) {
      if (command.matcher(line).find()) {
        var stripped = line.strip();
        var help = new LinkedHashMap<String, Object>();
        help.put("applicability", applicability(stripped, offered));
        help.put("command", stripped);
        commands.add(help);
      }
    }
    return commands;
  }

  private static String applicability(String command, List<String> offered) {
    if (command.contains("<") || command.contains("…")) {
      return "placeholders";
    }
    var words = List.of(command.split(" ", -1));
    return words.stream().anyMatch(word -> offered.stream().anyMatch(name -> names(word, name)))
        ? "maybe"
        : "exact";
  }

  /// Whether `word` of a command is `name`, or gives it as an option's value or in a list.
  private static boolean names(String word, String name) {
    return word.equals(name)
        || word.startsWith(name + "=")
        || word.endsWith("=" + name)
        || word.contains("," + name);
  }
}
