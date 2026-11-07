package com.example.money;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class Payments {
  private Payments() {}

  public static List<Integer> large(Flux<Integer> amounts) {
    return amounts.filter(amount -> amount >= 100).collectList().block();
  }

  public static List<Integer> firstTwo(Flux<Integer> amounts) {
    return amounts.take(2).collectList().block();
  }

  public static Integer orZero(Mono<Integer> amount) {
    return amount.onErrorReturn(0).block();
  }

  public static Boolean anyLarge(Flux<Integer> amounts) {
    return amounts.any(amount -> amount >= 100).block();
  }

  /// Each delay comes through once it has passed.
  public static List<Integer> arrivals(Flux<Integer> delays) {
    return delays
        .flatMap(delay -> Mono.just(delay).delayElement(Duration.ofMillis(delay)))
        .collectList()
        .block();
  }
}
