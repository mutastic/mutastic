package com.example.rates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RatesTest {
  @Test
  void lateFeesAccrueAfterAWeek() {
    assertThat(Rates.lateFee(2)).isZero();
    assertThat(Rates.lateFee(20)).isEqualTo(31); // nothing checks day 7 or 8
  }

  @Test
  void aPositiveBalanceIsInCredit() {
    assertThat(Rates.inCredit(50)).isTrue(); // nothing checks zero
  }

  @Test
  void jitterIsAPrice() {
    assertThat(Rates.jitter(100)).isPositive(); // too weak to tell `+` from `-`
  }
}
