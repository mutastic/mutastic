package io.mutastic.api;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/// A project's own decision, from `mutastic.yaml`: skip the mutants in some code, mutate code a
/// built-in filter would skip as arid, or name calls of its own that only write output, with the
/// reason.
///
/// @param kind `skip:`, `mutate:` or `output:`
/// @param target a class or method pattern: `com.acme.generated.**` (any depth), `*` (one name),
///   `com.acme.Money#toString` (one method). An `output:` rule's names the methods called,
///   `com.acme.AuditLog#*`.
/// @param arid a `mutate:` rule's `arid:` list, the ids of the arid rules it brings back
///   (`mutastic:waits`); empty for every arid skip, and for the other kinds
@Internal
public record Rule(Kind kind, String target, String reason, List<String> arid) {
  /// What a rule does with the code it names.
  public enum Kind {
    /// Skip the mutants in it.
    SKIP,
    /// Mutate what mutastic would skip in it as arid.
    MUTATE,
    /// Calls to it only write output, as a logger's do: `mutastic:output` skips them, and what only
    /// feeds them.
    OUTPUT;

    /// `skip`, as the file says it.
    public String written() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public Rule {
    arid = List.copyOf(arid);
  }

  /// A `skip:` or a `mutate:` rule.
  public Rule(boolean mutate, String target, String reason, List<String> arid) {
    this(mutate ? Kind.MUTATE : Kind.SKIP, target, reason, arid);
  }

  /// A `skip:` or a `mutate:` rule with no `arid:` list.
  public Rule(boolean mutate, String target, String reason) {
    this(mutate, target, reason, List.of());
  }

  /// An `output:` rule: calls of the methods `target` names only write output.
  public static Rule output(String target, String reason) {
    return new Rule(Kind.OUTPUT, target, reason, List.of());
  }

  /// Whether it's a `mutate:` rule.
  public boolean mutate() {
    return kind == Kind.MUTATE;
  }

  /// Whether it's a `skip:` rule.
  public boolean skip() {
    return kind == Kind.SKIP;
  }

  /// Whether the rule covers method `method` of class `className` (`com.acme.Money`).
  public boolean covers(String className, String method) {
    var pattern = pattern(target);
    return pattern.matcher(className + "#" + method).matches()
        || pattern.matcher(className).matches();
  }

  /// `skip: com.acme.generated.**`, as the rule was written.
  public String written() {
    return kind.written() + ": " + target;
  }

  private static Pattern pattern(String target) {
    var regex =
        Pattern.quote(target.replace("**", "\u0000"))
            .replace("*", "\\E[^.#]*\\Q")
            .replace("\u0000", "\\E.*\\Q");
    return Pattern.compile(regex);
  }
}
