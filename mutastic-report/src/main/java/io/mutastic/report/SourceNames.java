package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Pattern;

/// How the source names the code a mutant is in, from the names javac gives it: `Shipping$Zone` is
/// `Shipping.Zone`; `Shipping$1` is an anonymous class in `Shipping`; `Shipping$1Sum` is the local
/// class `Sum`; `lambda$heavyOnes$0` is a lambda in `heavyOnes()`.
final class SourceNames {
  private static final Pattern LAMBDA = Pattern.compile("lambda\\$(?<enclosing>.+)\\$\\d+");
  private static final Pattern NUMBERED = Pattern.compile("(?<number>\\d+)(?<name>.*)");

  private SourceNames() {}

  /// Where a mutant is, as the source has it.
  ///
  /// @param className the named classes it's in, `Shipping.Zone`, up to any anonymous or local one
  /// @param method the method, or for a lambda the method it's in
  /// @param kind what the code is when it isn't a plain method: `a lambda`, `an anonymous class`,
  ///   `the local class Sum`
  record Where(String className, String method, Optional<String> kind) {}

  static Where of(Mutant mutant) {
    var internal = mutant.className();
    var parts = internal.substring(internal.lastIndexOf('/') + 1).split("\\$", -1);
    var named = new ArrayList<String>();
    Optional<String> kind = Optional.empty();
    for (String part : parts) {
      var numbered = NUMBERED.matcher(part);
      if (numbered.matches()) {
        kind =
            Optional.of(
                numbered.group("name").isEmpty()
                    ? "an anonymous class"
                    : "the local class `" + numbered.group("name") + "`");
        break;
      }
      named.add(part);
    }
    var lambda = LAMBDA.matcher(mutant.methodName());
    if (lambda.matches()) {
      return new Where(String.join(".", named), lambda.group("enclosing"), Optional.of("a lambda"));
    }
    return new Where(String.join(".", named), mutant.methodName(), kind);
  }

  /// `ShippingTest$Zones` as the source writes it: `ShippingTest.Zones`.
  static String sourceName(String binaryName) {
    return binaryName.replace('$', '.');
  }
}
