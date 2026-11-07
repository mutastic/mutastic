package com.example.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Java can call an inline function like any other method: this one runs its own body. */
class FromJavaTest {
  @Test
  void callsTheInlineFunctionItself() {
    assertEquals(15, InlinesKt.calledFromJava(() -> 5));
  }
}
