package io.mutastic.report;

import java.time.Duration;
import java.time.Instant;

/// How long the machine slept during a run, from when it started. A run's Duration counts the time
/// the machine was awake, as `System.nanoTime` does: it stops while a Mac sleeps, so a run the Mac
/// slept through for four minutes said 15.4s. The wall clock keeps going, so the two read over the
/// same stretch tell the sleep apart, and the summary and `run-finished` say it when it's worth
/// saying.
///
/// @param clock the wall clock when the run started
/// @param awake [System#nanoTime] when the run started
public record Slept(Instant clock, long awake) {
  /// The least sleep worth saying. Under it, the wall clock's own corrections and the moment
  /// between reading one clock and the other are as likely the cause.
  static final Duration AT_LEAST = Duration.ofSeconds(5);

  /// And at least a tenth of the clock's time: with less, Duration is still about what the clock
  /// says, and the line would only be noise.
  static final int TENTHS = 10;

  /// Both clocks, now.
  public static Slept now() {
    return new Slept(Instant.now(), System.nanoTime());
  }

  /// How long the machine slept from the start until now, when it's worth saying; else zero.
  public Duration untilNow() {
    return until(Instant.now(), System.nanoTime());
  }

  /// How long the machine slept from the start until the clocks read these, when it's worth saying;
  /// else zero.
  ///
  /// @param clockNow the wall clock now
  /// @param awakeNow [System#nanoTime] now
  public Duration until(Instant clockNow, long awakeNow) {
    return noticed(Duration.between(clock, clockNow), Duration.ofNanos(awakeNow - awake));
  }

  /// The clock's time less the awake time, when it's at least five seconds and a tenth of the
  /// clock's time; else zero. A clock set back gives zero.
  static Duration noticed(Duration clock, Duration awake) {
    var slept = clock.minus(awake);
    return slept.compareTo(AT_LEAST) >= 0 && slept.multipliedBy(TENTHS).compareTo(clock) >= 0
        ? slept
        : Duration.ZERO;
  }

  /// The summary's line: `The machine slept for 3m53s during the run, which Duration leaves out.`
  public static String line(Duration slept) {
    return "The machine slept for %s during the run, which Duration leaves out."
        .formatted(HumanText.seconds(slept));
  }
}
