package com.example.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PaymentsTest {
  @Test
  void onlyLargeOnes() {
    assertEquals(List.of(100), Payments.large(Flux.just(100, 1)));
  }

  @Test
  void twoAreEnoughWhenThereAreTwo() {
    assertEquals(List.of(1, 2), Payments.firstTwo(Flux.just(1, 2)));
  }

  @Test
  void aValuePassesThrough() {
    assertEquals(1, Payments.orZero(Mono.just(1)));
  }

  @Test
  void allLarge() {
    assertEquals(true, Payments.anyLarge(Flux.just(100, 200)));
  }

  @Test
  void oneArrives() {
    assertEquals(List.of(1), Payments.arrivals(Flux.just(1)));
  }
}
