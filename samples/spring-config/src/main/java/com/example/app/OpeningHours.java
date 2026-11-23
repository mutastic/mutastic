package com.example.app;

import java.time.Clock;
import java.time.LocalTime;
import org.springframework.stereotype.Service;

/// Whether the shop is open, by the clock it's given.
@Service
public class OpeningHours {
  private final Clock clock;

  public OpeningHours(Clock clock) {
    this.clock = clock;
  }

  public boolean isOpen() {
    return isOpenAt(LocalTime.now(clock).getHour());
  }

  /// Package-private, for the tests in this package.
  boolean isOpenAt(int hour) {
    return hour >= 9 && hour < 17;
  }
}
