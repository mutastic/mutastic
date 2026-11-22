package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// The cards for a thread's call taken away, or a pool's wait that never times out. A test that
/// catches one of these has the same shape every time: it waits for the thread, then looks. So the
/// card names that test, not just the call. `ThreadCardTruthTest` runs each suggested test against
/// each mutant, in every shape javac compiles it to.
final class ThreadCalls {
  private ThreadCalls() {}

  /// The JDK's pools, as a call's owner names them. A pool of the project's own, or a call through
  /// one of its fields' types, gets the card every call gets.
  private static final Set<String> POOLS =
      Set.of(
          "java/util/concurrent/Executor",
          "java/util/concurrent/ExecutorService",
          "java/util/concurrent/ScheduledExecutorService",
          "java/util/concurrent/AbstractExecutorService",
          "java/util/concurrent/ThreadPoolExecutor",
          "java/util/concurrent/ScheduledThreadPoolExecutor",
          "java/util/concurrent/ForkJoinPool");

  private static final Set<String> LOCKS =
      Set.of(
          "java/util/concurrent/locks/Lock",
          "java/util/concurrent/locks/ReentrantLock",
          "java/util/concurrent/locks/ReentrantReadWriteLock$ReadLock",
          "java/util/concurrent/locks/ReentrantReadWriteLock$WriteLock");

  private static final Set<String> BUILDERS =
      Set.of(
          "java/lang/Thread$Builder",
          "java/lang/Thread$Builder$OfPlatform",
          "java/lang/Thread$Builder$OfVirtual");

  /// `INVOKEVIRTUAL java/lang/Thread.start()V`: the owner, the name and the descriptor.
  private static final Pattern CALL =
      Pattern.compile("\\S+ (?<owner>[^.]+)\\.(?<name>[^(]+)(?<descriptor>\\(.*)");

  /// `Thread.currentThread().interrupt()`, which sets the flag again after an
  /// `InterruptedException` cleared it.
  private static final Pattern REINTERRUPT =
      Pattern.compile("\\bcurrentThread\\(\\)\\s*\\.\\s*interrupt\\(\\)");

  private static final Pattern DAEMON = Pattern.compile("\\bsetDaemon\\(\\s*true\\s*\\)");

  /// The card for a removed call that starts a thread, hands a pool work, interrupts a thread,
  /// frees a lock, marks a thread daemon or gives it a handler; empty for any other call.
  ///
  /// @param statement the call's statement as the source has it, or empty if there's none
  static Optional<Advice> removed(Mutant mutant, Inputs seen, String statement) {
    var call = CALL.matcher(mutant.original());
    if (!call.matches()) {
      return Optional.empty();
    }
    var owner = call.group("owner");
    var name = call.group("name");
    var called = "`%s()`".formatted(name);
    var method = Advice.method(mutant);
    return gap(owner, name, call.group("descriptor"), statement)
        .map(
            gap ->
                new Advice(
                    Optional.empty(),
                    Optional.empty(),
                    "the call to %s is removed".formatted(called),
                    "call removed",
                    gap.headline().formatted(method),
                    Advice.inputsSeen(seen),
                    gap.fix().formatted(method)));
  }

  /// What a test of the call has to do: the headline and the fix, each with `%s` for the method.
  private record Gap(String headline, String fix) {}

  private static final Gap STARTED =
      new Gap(
          "no test waits for the thread started in %s and checks what it did",
          "wait for the thread to finish (`join()` it, or wait on a latch its task counts down),"
              + " then assert what its task did");

  private static final Gap POOLED =
      new Gap(
          "no test waits for the task handed to the pool in %s and checks what it did",
          "wait until the task has run (a latch it counts down, or `shutdown()` then"
              + " `awaitTermination()`), then assert what it did");

  private static final Gap UNLOCKED =
      new Gap(
          "no test checks that the lock is free again after %s",
          "call %s, then take the lock from another thread with `tryLock()` and assert it succeeds"
              + " (for a read lock, take the write lock)");

  private static final Gap INTERRUPTED =
      new Gap(
          "no test checks that a thread interrupted in %s stops",
          "start a thread that waits (in `sleep()` or a queue's `take()`), have %s interrupt it,"
              + " and assert it ends: `join()` it with a timeout, then `isAlive()` is false");

  /// `Thread.currentThread().interrupt()` in a `catch`, which keeps the interrupt for the caller.
  private static final Gap REINTERRUPTED =
      new Gap(
          "no test checks that %s leaves the thread interrupted",
          "interrupt the test's thread, call %s, and assert that `Thread.interrupted()` is true"
              + " after it (which also clears it)");

  private static final Gap DAEMONS =
      new Gap(
          "no test checks whether a thread from %s lets the JVM exit",
          "get a thread from %s and assert that `isDaemon()` is true");

  private static final Gap HANDLED =
      new Gap(
          "no test checks what happens when a task on a thread from %s throws",
          "throw from a task on a thread from %s, run with `start()` or `execute()` (an exception"
              + " from `submit()` never reaches the handler), and assert what the handler did");

  private static Optional<Gap> gap(String owner, String name, String descriptor, String statement) {
    if (starts(owner, name)) {
      return Optional.of(STARTED);
    }
    if (POOLS.contains(owner) && (name.equals("execute") || name.equals("submit"))) {
      return Optional.of(POOLED);
    }
    if (LOCKS.contains(owner) && name.equals("unlock")) {
      return Optional.of(UNLOCKED);
    }
    return owner.equals("java/lang/Thread")
        ? threadGap(name, descriptor, statement)
        : Optional.empty();
  }

  /// Whether the call's owner is one of the JDK's pools.
  static boolean isPool(String owner) {
    return POOLS.contains(owner);
  }

  private static boolean starts(String owner, String name) {
    if (owner.equals("java/lang/Thread")) {
      return name.equals("start") || name.equals("startVirtualThread");
    }
    return BUILDERS.contains(owner) && name.equals("start");
  }

  /// A `Thread`'s own calls: interrupt it, mark it daemon, give it a handler. Which way an
  /// interrupt goes, and what a daemon flag is set to, only the statement says.
  private static Optional<Gap> threadGap(String name, String descriptor, String statement) {
    return switch (name) {
      case "interrupt" ->
          statement.isEmpty()
              ? Optional.empty()
              : Optional.of(REINTERRUPT.matcher(statement).find() ? REINTERRUPTED : INTERRUPTED);
      case "setDaemon" -> Optional.of(DAEMONS).filter(gap -> DAEMON.matcher(statement).find());
      case "setUncaughtExceptionHandler" ->
          Optional.of(HANDLED)
              .filter(
                  gap -> descriptor.startsWith("(Ljava/lang/Thread$UncaughtExceptionHandler;)"));
      default -> Optional.empty();
    };
  }

  /// The card for a pool's `awaitTermination` that's always true, `advice` with the test that makes
  /// it time out: the code after a missed deadline never runs. `advice` for any other condition, or
  /// the other way round.
  static Advice awaited(Mutant mutant, Advice advice, String condition, boolean alwaysTrue) {
    var operands = mutant.operands();
    if (!alwaysTrue || operands.size() != 1 || !AWAITS.matcher(operands.getFirst()).find()) {
      return advice;
    }
    var method = Advice.method(mutant);
    return new Advice(
        advice.before(),
        advice.after(),
        advice.change(),
        advice.changedTo(),
        "no test has %s time out in %s".formatted(condition, method),
        advice.notes(),
        "give the pool a task still running when the wait ends, and assert what %s does then"
            .formatted(method));
  }

  private static final Pattern AWAITS = Pattern.compile("(^|\\.)awaitTermination\\(.*\\)$");
}
