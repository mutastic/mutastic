package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CarouselTest {
  @Test
  void movingPastTheLastSlideWrapsRound() {
    assertEquals(1, Carousel.slide(4, 3));
  }
}
