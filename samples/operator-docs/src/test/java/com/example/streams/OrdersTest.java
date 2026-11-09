package com.example.streams;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.streams.Orders.Order;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrdersTest {
  @Test
  void aPaidOrder() {
    assertEquals(10, Orders.paidTotal(List.of(new Order(10, true))));
  }
}
