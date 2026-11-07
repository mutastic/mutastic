package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;
import org.junit.rules.ExternalResource;

public class RuleSiteTest {
  int value;

  @Rule
  public final ExternalResource resource =
      new ExternalResource() {
        @Override
        protected void before() {
          value = Sites.ruleValue(1);
          Sites.ruleThrows(1);
        }

        @Override
        protected void after() {
          assertEquals(2, Sites.ruleAfterChecked(1));
          Sites.ruleAfterThrows(1);
        }
      };

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  public void readsNothing() {}
}
