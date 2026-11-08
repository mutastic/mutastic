package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class BranchTest {
  @Test
  void aRenameIsAnnounced() {
    var branch = new Branch("High Street");
    var heard = new ArrayList<PropertyChangeEvent>();
    branch.addPropertyChangeListener(heard::add);

    branch.setName("Market Square");

    assertEquals("Market Square", heard.get(0).getNewValue());
  }

  @Test
  void anOpeningHourIsAnHourOfTheDay() {
    var branch = new Branch("High Street");

    branch.setOpeningHour(8);

    assertEquals(8, branch.getOpeningHour());
    assertThrows(IllegalArgumentException.class, () -> branch.setOpeningHour(24));
  }
}
