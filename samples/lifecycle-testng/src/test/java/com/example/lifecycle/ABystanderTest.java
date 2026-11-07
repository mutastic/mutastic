package com.example.lifecycle;

import org.testng.annotations.Test;

/// Runs before every other class, and touches nothing: what ran after it isn't its doing.
public class ABystanderTest {
  @Test
  public void touchesNothing() {}
}
