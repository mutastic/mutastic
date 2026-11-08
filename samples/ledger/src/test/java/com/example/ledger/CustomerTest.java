package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerTest {
  @Test
  void aNewEmailIsTrimmedAndAnnounced() {
    var customer = new Customer("Ada");
    var heard = new ArrayList<PropertyChangeEvent>();
    customer.addPropertyChangeListener(heard::add);

    customer.setEmail(" ada@example.com ");

    assertEquals("ada@example.com", customer.getEmail());
    assertEquals(
        List.of("email"), heard.stream().map(PropertyChangeEvent::getPropertyName).toList());
  }

  @Test
  void anAddressIsKept() {
    var customer = new Customer("Ada");

    customer.setAddress("12 St James's Square");

    assertEquals("12 St James's Square", customer.getAddress());
  }
}
