package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderTest {
  @Test
  void aNameIsRequired() {
    assertThat(Validators.SHARED.validate(new Order(null, "abc", 1))).hasSize(1);
  }

  @Test
  void aValidOrderPasses() {
    assertThat(Validators.SHARED.validate(new Order("a", "abc", 1))).isEmpty();
  }
}
