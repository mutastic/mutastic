package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuleTest {
  @Test
  void twoStarsAreAnyDepthAndOneIsOneName() {
    var deep = new Rule(false, "com.acme.generated.**", "protobuf output");
    var one = new Rule(false, "com.acme.*.Dto", "data");

    assertThat(deep.covers("com.acme.generated.v1.Order", "total")).isTrue();
    assertThat(deep.covers("com.acme.Order", "total")).isFalse();
    assertThat(one.covers("com.acme.orders.Dto", "id")).isTrue();
    assertThat(one.covers("com.acme.orders.v1.Dto", "id")).isFalse();
  }

  @Test
  void aMethodIsNamedAfterItsClass() {
    var rule = new Rule(true, "com.acme.Money#toString", "shown to customers");

    assertThat(rule.covers("com.acme.Money", "toString")).isTrue();
    assertThat(rule.covers("com.acme.Money", "hashCode")).isFalse();
    assertThat(rule.covers("com.acme.Moneybox", "toString")).isFalse();
    assertThat(rule.written()).isEqualTo("mutate: com.acme.Money#toString");
  }
}
