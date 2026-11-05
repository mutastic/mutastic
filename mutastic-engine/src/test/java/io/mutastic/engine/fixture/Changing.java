package io.mutastic.engine.fixture;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/// Calls that answer differently each run, and the same calls made to answer the same.
public final class Changing {
  private Changing() {}

  static int unseeded() {
    return new Random().nextInt(10) + ThreadLocalRandom.current().nextInt(3);
  }

  static int seeded() {
    return new Random(42L).nextInt(10);
  }

  static long times(Clock clock) {
    return Instant.now().toEpochMilli() + Instant.now(clock).toEpochMilli() + System.nanoTime();
  }

  static String ids(List<String> names, Random random) {
    Collections.shuffle(names);
    Collections.shuffle(names, random);
    return UUID.randomUUID().toString() + Math.random();
  }
}
