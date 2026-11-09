package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FieldsTest {
  @Test
  void anEmptyFieldIsntFilled() {
    assertEquals(1, Fields.filled(List.of("Ann", "")));
  }
}
