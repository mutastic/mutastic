package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// The cards for a call that releases something, taken away: a `close()`, a pool's `shutdown()`, a
/// shutdown hook, a `Cleaner`'s `clean()`. Such a mutant survives because tests rarely look at the
/// released resource or take the failure path, not because no test should: so each card names the
/// thing to look at and how. `CleanupCardTruthTest` runs each suggested test against each mutant,
/// in every shape javac compiles it to.
final class CleanupCalls {
  private CleanupCalls() {}

  /// `INVOKEVIRTUAL java/io/Reader.close()V`: the owner, the name and the descriptor.
  private static final Pattern CALL =
      Pattern.compile("\\S+ (?<owner>[^.]+)\\.(?<name>[^(]+)(?<descriptor>\\(.*)");

  private static final Set<String> STATEMENTS =
      Set.of(
          "java/sql/Statement",
          "java/sql/PreparedStatement",
          "java/sql/CallableStatement",
          "java/sql/ResultSet");

  /// What a test of the call has to do: the headline and the fix, each with `%s` for the method.
  private record Gap(String headline, String fix) {}

  /// A `close()` in a `finally`, which javac copies onto each way out: the mutant takes every copy.
  private static final Gap CLOSED_FINALLY =
      new Gap(
          "no test checks that the resource is closed after %s",
          "pass in a resource you can inspect (a stub with a closed flag, or a spy) and assert"
              + " it's closed when %s returns, and when it throws");

  /// A `close()` on one way through: often a `catch`, which only a failure reaches.
  private static final Gap CLOSED =
      new Gap(
          "no test takes the way through %s that closes the resource and checks it's closed",
          "pass in a resource you can inspect (a stub with a closed flag, or a spy), take the way"
              + " to this `close()` (if it's in a `catch`, make the work throw), and assert it's"
              + " closed");

  private static final Gap CLOSED_QUIETLY =
      new Gap(
          "no test checks that the resource is closed after %s",
          "pass in a resource you can inspect and assert it's closed after %s; for an output"
              + " stream, read the data back, as a lost close can lose the last of it too");

  private static final Gap CONNECTION =
      new Gap(
          "no test checks that the connection goes back after %s",
          "take connections from a `DataSource` you pass in, then verify `close()` on a mock, or"
              + " use a pool of one with a short timeout and call %s twice");

  private static final Gap STATEMENT =
      new Gap(
          "no test checks this `close()` in %s: closing the connection closes it too",
          "verify `close()` on a mock, or use try-with-resources so the question goes away");

  private static final Gap SHUTDOWN =
      new Gap(
          "no test checks that %s shuts its pool down",
          "pass in the executor (or a factory for it) and assert `isShutdown()` after %s, or make"
              + " the pool in a try-with-resources (JDK 19 and later)");

  private static final Gap HOOKED =
      new Gap(
          "no test checks that %s registers its shutdown hook",
          "call %s, then assert that `Runtime.getRuntime().removeShutdownHook(hook)` returns true");

  private static final Gap CLEANED =
      new Gap(
          "no test checks that %s releases the resource now, not when the garbage collector gets"
              + " to it",
          "call %s, then assert the cleanup's effect right away");

  /// The card for a removed call that releases something; empty for any other call.
  static Optional<Advice> removed(Mutant mutant, Inputs seen) {
    var call = CALL.matcher(mutant.original());
    if (!call.matches()) {
      return Optional.empty();
    }
    var name = call.group("name");
    var method = Advice.method(mutant);
    return gap(call.group("owner"), name, call.group("descriptor"), !mutant.copies().isEmpty())
        .map(
            gap ->
                new Advice(
                    Optional.empty(),
                    Optional.empty(),
                    "the call to `%s()` is removed".formatted(name),
                    "call removed",
                    gap.headline().formatted(method),
                    Advice.inputsSeen(seen),
                    gap.fix().formatted(method)));
  }

  private static Optional<Gap> gap(
      String owner, String name, String descriptor, boolean inFinally) {
    if (name.equals("close") && descriptor.equals("()V")) {
      if (owner.equals("java/sql/Connection")) {
        return Optional.of(CONNECTION);
      }
      if (STATEMENTS.contains(owner)) {
        return Optional.of(STATEMENT);
      }
      return Optional.of(inFinally ? CLOSED_FINALLY : CLOSED);
    }
    if (name.equals("closeQuietly") && descriptor.endsWith(")V")) {
      return Optional.of(CLOSED_QUIETLY);
    }
    if (ThreadCalls.isPool(owner) && (name.equals("shutdown") || name.equals("shutdownNow"))) {
      return Optional.of(SHUTDOWN);
    }
    if (owner.equals("java/lang/Runtime") && name.equals("addShutdownHook")) {
      return Optional.of(HOOKED);
    }
    return owner.equals("java/lang/ref/Cleaner$Cleanable") && name.equals("clean")
        ? Optional.of(CLEANED)
        : Optional.empty();
  }
}
