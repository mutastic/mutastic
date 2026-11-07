package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;
import org.junit.rules.ExternalResource;

public class ClassRuleSiteTest {
  static int value;

  @ClassRule
  public static final ExternalResource RESOURCE =
      new ExternalResource() {
        @Override
        protected void before() {
          value = Sites.classRuleValue(1);
          Sites.classRuleThrows(1);
        }

        @Override
        protected void after() {
          assertEquals(2, Sites.classRuleAfterChecked(1));
          Sites.classRuleAfterThrows(1);
        }
      };

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  public void readsNothing() {}
}
