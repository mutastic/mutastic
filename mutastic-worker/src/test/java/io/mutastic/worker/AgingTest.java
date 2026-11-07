package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgingTest {
  /// Swaps that stay as fast as they began lose nothing, however many there are.
  @Test
  void aWorkerWhoseSwapsDontSlowIsKept() {
    var aging = new Aging(100);

    for (int swap = 0; swap < 1000; swap++) {
      assertThat(aging.swapTook(50)).isFalse();
    }
  }

  /// What counts is the time lost against the fastest swap, added up: not the time swaps took.
  @Test
  void aWorkerIsReplacedOnceSlowerSwapsHaveCostWhatANewOneWould() {
    var aging = new Aging(100);

    assertThat(aging.swapTook(1000)).isFalse();
    assertThat(aging.swapTook(10)).isFalse();
    assertThat(aging.swapTook(60)).as("50 lost").isFalse();
    assertThat(aging.swapTook(59)).as("49 more").isFalse();
    assertThat(aging.swapTook(11)).as("one more, and that's what a new one costs").isTrue();
  }
}
