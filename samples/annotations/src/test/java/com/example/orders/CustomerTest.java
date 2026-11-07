package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerTest {
  @Test
  void aNameIsRequired() {
    assertThat(Validators.SHARED.validate(new Customer(" ", "a@example.com"))).hasSize(1);
  }
}
