package io.mutastic.cli;

import io.mutastic.report.LiveEvents;
import java.time.Duration;

/// Where a run's live events go, and how often its `progress` events do: stdout's on the
/// heartbeat's cadence, the Gradle plugin's file every second. Not thread-safe: progress holds its
/// lock around every call.
final class EventSink {
  private final LiveEvents events;
  private final long every;
  private long last;

  /// @param now when it starts, by the progress clock, which `every` is counted from
  EventSink(LiveEvents events, Duration every, long now) {
    this.events = events;
    this.every = every.toNanos();
    this.last = now;
  }

  LiveEvents events() {
    return events;
  }

  /// Whether a `progress` event is due at `now`; if it is, it's counted as written.
  boolean due(long now) {
    if (now - last < every) {
      return false;
    }
    last = now;
    return true;
  }
}
