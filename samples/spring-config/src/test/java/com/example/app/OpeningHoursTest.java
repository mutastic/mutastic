package com.example.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = TestApp.class)
class OpeningHoursTest {
  @Autowired OpeningHours hours;

  @Test
  void isOpenAtTen() {
    assertThat(hours.isOpen()).isTrue();
  }

  @Test
  void opensAtNine() {
    assertThat(hours.isOpenAt(8)).isFalse();
    assertThat(hours.isOpenAt(9)).isTrue();
  }

  @Test
  void closesAtFive() {
    assertThat(hours.isOpenAt(16)).isTrue();
    assertThat(hours.isOpenAt(17)).isFalse();
  }
}
