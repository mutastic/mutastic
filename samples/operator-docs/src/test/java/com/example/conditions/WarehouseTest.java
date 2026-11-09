package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.conditions.Warehouse.Order;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarehouseTest {
  @Test
  void oneOrderOfTwoShips() {
    var orders = List.of(new Order("a1", true), new Order("b2", false));
    assertEquals(1, Warehouse.toShip(orders).size());
  }
}
