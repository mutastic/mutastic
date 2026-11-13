package io.mutastic.cli;

import io.mutastic.api.MutantCost;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// What each worker is running, and what the mutants should take between them and have taken so
/// far: for the worker lines, the bar weighted by cost and what's left. Not thread-safe: progress
/// holds its lock around every call.
final class Workload {
  /// A test in the coverage pass is slow after a minute: there's nothing yet to measure it by.
  static final Duration SLOW_TEST = Duration.ofMinutes(1);

  /// Nothing is SLOW before two seconds.
  static final Duration SLOW_FLOOR = Duration.ofSeconds(2);

  /// What's left is said once this many mutants have run, and [#SETTLED] has passed.
  static final int RAN_ENOUGH = 3;

  static final Duration SETTLED = Duration.ofSeconds(10);

  /// A test or a mutant a worker has started.
  ///
  /// @param id the mutant's id, empty for a test
  /// @param doing what the worker's line says it runs
  /// @param started when, by the progress clock
  record Running(String id, String doing, long started, MutantCost cost) {
    /// A test in the coverage pass.
    static Running test(String test, long started) {
      return new Running("", test, started, new MutantCost(Duration.ZERO, SLOW_TEST, 1));
    }

    Snapshot.Worker at(long now) {
      var elapsed = Duration.ofNanos(now - started);
      return new Snapshot.Worker(
          id, doing, elapsed, cost.expected(), elapsed.compareTo(slowAfter()) > 0, cost.tests());
    }

    /// What its cost says, and never before [#SLOW_FLOOR]: a mutant whose tests take milliseconds
    /// would otherwise be marked for being a little slower than that, on every line.
    private Duration slowAfter() {
      return cost.slow().compareTo(SLOW_FLOOR) > 0 ? cost.slow() : SLOW_FLOOR;
    }
  }

  private final List<Optional<Running>> slots = new ArrayList<>();

  /// What each mutant started should take, by id, until it has its result.
  private final Map<String, Duration> expected = new HashMap<>();

  private Duration cost = Duration.ZERO;
  private Duration costDone = Duration.ZERO;

  /// Nothing running, on `workers` workers, and nothing yet to take.
  void reset(int workers) {
    slots.clear();
    for (int at = 0; at < workers; at++) {
      slots.add(Optional.empty());
    }
    expected.clear();
    cost = Duration.ZERO;
    costDone = Duration.ZERO;
  }

  /// The mutants will take `expected` between them.
  void planned(int workers, Duration expected) {
    reset(workers);
    cost = expected;
  }

  /// Every worker idle, as a phase starts.
  void idle() {
    slots.replaceAll(running -> Optional.empty());
  }

  /// Worker `worker` runs what's said.
  void started(int worker, Running running) {
    if (worker >= 0 && worker < slots.size()) {
      slots.set(worker, Optional.of(running));
    }
    if (!running.id().isEmpty()) {
      expected.put(running.id(), running.cost().expected());
    }
  }

  void idle(int worker) {
    if (worker >= 0 && worker < slots.size()) {
      slots.set(worker, Optional.empty());
    }
  }

  /// Mutant `id` has its result: what it should have taken is done.
  void finished(String id) {
    var took = expected.remove(id);
    if (took != null) {
      costDone = costDone.plus(took);
    }
  }

  /// Each worker's line, at `now`.
  List<Optional<Snapshot.Worker>> workers(long now) {
    return slots.stream().map(running -> running.map(started -> started.at(now))).toList();
  }

  /// How far the mutants are by cost, and what's left once [#RAN_ENOUGH] have run and [#SETTLED]
  /// has passed: the time so far, scaled by the cost still to run over the cost done. Nothing when
  /// no test reaches any mutant.
  Snapshot.Estimate estimate(Duration elapsed, int ran) {
    if (cost.isZero()) {
      return Snapshot.Estimate.NONE;
    }
    double weighted = seconds(costDone) / seconds(cost);
    if (ran < RAN_ENOUGH || elapsed.compareTo(SETTLED) < 0 || costDone.isZero()) {
      return new Snapshot.Estimate(Optional.of(weighted), Optional.empty());
    }
    double still = Math.max(0, seconds(cost.minus(costDone)) / seconds(costDone));
    var left = Duration.ofNanos((long) (elapsed.toNanos() * still));
    return new Snapshot.Estimate(Optional.of(weighted), Optional.of(left));
  }

  private static double seconds(Duration duration) {
    return duration.toNanos() / 1e9;
  }
}
