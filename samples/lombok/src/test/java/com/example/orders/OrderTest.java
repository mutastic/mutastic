package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderTest {
  @Test
  void theTotalAddsEveryLine() {
    var tea = new OrderLine("tea", new Money(450, "EUR"));
    tea.setQuantity(2);
    tea.setGiftMessage("for Grace");
    var order =
        Order.builder()
            .customer(new Customer("Ada"))
            .line(tea)
            .line(new OrderLine("cake", new Money(300, "EUR")))
            .build();

    assertEquals(new Money(1200, "EUR"), order.total());
  }

  @Test
  void goldCustomersGetTenPercentOffBigOrders() {
    var customer = new Customer("Grace");
    customer.setLoyaltyPoints(5000);
    var machines = new OrderLine("espresso machine", new Money(25_000, "EUR"));
    machines.setQuantity(2);
    var order = Order.builder().customer(customer).line(machines).build();

    assertEquals(45_000, order.discounted().getCents());
  }
}
