package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiptsTest {
  @Test
  void aReceiptKeepsTheTotal(@TempDir Path dir) {
    var tea = new OrderLine("tea", new Money(450, "EUR"));
    tea.setQuantity(2);
    var order = Order.builder().customer(new Customer("Ada")).line(tea).build();
    var file = dir.resolve("receipt.txt");

    Receipts.write(order, file);

    assertEquals(900, Receipts.totalIn(file));
  }
}
