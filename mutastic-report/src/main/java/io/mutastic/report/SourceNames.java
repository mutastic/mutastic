package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Pattern;

/// How the source names the code a mutant is in, from the names javac gives it: `Shipping$Zone` is
/// `Shipping.Zone`; `Shipping$1` is an anonymous class in `Shipping`; `Shipping$1Sum` is the local
/// class `Sum`; `lambda$heavyOnes$0` is a lambda in `heavyOnes()`, as kotlinc's
/// `heavyOnes$lambda$0` is.
final class SourceNames {
  private static final Pattern LAMBDA = Pattern.compile("lambda\\$(?<enclosing>.+)\\$\\d+");

  /// kotlinc's name for one, `labels$lambda$0`, or `v_delegate$lambda$0` for a property's.
  private static final Pattern KOTLIN_LAMBDA =
      Pattern.compile("(?<enclosing>.+?)(_delegate)?\\$lambda\\$\\d+(\\$\\w+)*");

  /// A suspending lambda is a class of its own, `FileKt$doubles$1`, its code in `invokeSuspend`.
  private static final Pattern SUSPEND_LAMBDA =
      Pattern.compile("(?<outer>.+)\\$(?<enclosing>[^$]+)\\$\\d+");

  private static final Pattern NUMBERED = Pattern.compile("(?<number>\\d+)(?<name>.*)");

  private SourceNames() {}

  /// Where a mutant is, as the source has it.
  ///
  /// @param className the named classes it's in, `Shipping.Zone`, up to any anonymous or local one
  /// @param method the method, or for a lambda the method it's in
  /// @param kind what the code is when it isn't a plain method: `a lambda`, `an anonymous class`,
  ///   `the local class Sum`
  record Where(String className, String method, Optional<String> kind) {}

  /// The class the Compose compiler keeps a file's composable lambdas in, as singletons:
  /// `ComposableSingletons$FocusAppKt`, with the lambdas' code in methods (`lambda__1906$lambda$0`)
  /// or classes of their own. Their names say nothing of where they are, so only the file's class
  /// is named; the line says the rest.
  private static final Pattern COMPOSABLE_SINGLETONS =
      Pattern.compile("ComposableSingletons\\$(?<outer>[^$]+).*");

  static final String COMPOSABLE_LAMBDA = "a composable lambda";

  static Where of(Mutant mutant) {
    var internal = mutant.className();
    var composable = composableLambda(internal);
    if (composable.isPresent()) {
      return composable.get();
    }
    var named = named(internal);
    var lambda = LAMBDA.matcher(mutant.methodName());
    if (lambda.matches()) {
      return new Where(named.className(), lambda.group("enclosing"), Optional.of("a lambda"));
    }
    return kotlinLambda(mutant, named.className())
        .orElse(new Where(named.className(), mutant.methodName(), named.kind()));
  }

  /// The named classes a class is in, up to any anonymous or local one, and which that is.
  private record Named(String className, Optional<String> kind) {}

  private static Named named(String internal) {
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
    return new Named(String.join(".", named), kind);
  }

  private static Optional<Where> composableLambda(String internal) {
    var singletons =
        COMPOSABLE_SINGLETONS.matcher(internal.substring(internal.lastIndexOf('/') + 1));
    return singletons.matches()
        ? Optional.of(new Where(singletons.group("outer"), "", Optional.of(COMPOSABLE_LAMBDA)))
        : Optional.empty();
  }

  /// Where a lambda of kotlinc's is, by its method's name or a suspending lambda's class.
  private static Optional<Where> kotlinLambda(Mutant mutant, String className) {
    var internal = mutant.className();
    var suspending = SUSPEND_LAMBDA.matcher(internal.substring(internal.lastIndexOf('/') + 1));
    if (mutant.methodName().equals("invokeSuspend") && suspending.matches()) {
      return Optional.of(
          new Where(
              suspending.group("outer").replace('$', '.'),
              suspending.group("enclosing"),
              Optional.of("a lambda")));
    }
    var named = KOTLIN_LAMBDA.matcher(mutant.methodName());
    return named.matches()
        ? Optional.of(new Where(className, named.group("enclosing"), Optional.of("a lambda")))
        : Optional.empty();
  }

  /// `ShippingTest$Zones` as the source writes it: `ShippingTest.Zones`.
  static String sourceName(String binaryName) {
    return binaryName.replace('$', '.');
  }
}
