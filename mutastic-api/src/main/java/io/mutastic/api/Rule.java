package io.mutastic.api;

import java.util.List;
import java.util.regex.Pattern;

/// A project's own decision, from `mutastic.yaml`: skip the mutants in some code, or mutate code a
/// built-in filter would skip as arid, with the reason.
///
/// @param mutate `mutate:`, else `skip:`
/// @param target a class or method pattern: `com.acme.generated.**` (any depth), `*` (one name),
///   `com.acme.Money#toString` (one method)
/// @param arid a `mutate:` rule's `arid:` list, the ids of the arid rules it brings back
///   (`mutastic:waits`); empty for every arid skip, and for a `skip:` rule
@Internal
public record Rule(boolean mutate, String target, String reason, List<String> arid) {
  public Rule {
    arid = List.copyOf(arid);
  }

  /// A rule with no `arid:` list.
  public Rule(boolean mutate, String target, String reason) {
    this(mutate, target, reason, List.of());
  }

  /// Whether the rule covers method `method` of class `className` (`com.acme.Money`).
  public boolean covers(String className, String method) {
    var pattern = pattern(target);
    return pattern.matcher(className + "#" + method).matches()
        || pattern.matcher(className).matches();
  }

  /// `skip: com.acme.generated.**`, as the rule was written.
  public String written() {
    return (mutate ? "mutate: " : "skip: ") + target;
  }

  private static Pattern pattern(String target) {
    var regex =
        Pattern.quote(target.replace("**", "\u0000"))
            .replace("*", "\\E[^.#]*\\Q")
            .replace("\u0000", "\\E.*\\Q");
    return Pattern.compile(regex);
  }
}
