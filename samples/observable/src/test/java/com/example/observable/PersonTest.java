package com.example.observable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonTest {
  @Test
  void namingSomeoneTellsTheListeners() {
    var person = new Person();
    var heard = new ArrayList<PropertyChangeEvent>();
    person.addPropertyChangeListener(heard::add);

    person.setName("Ada");

    assertEquals("Ada", person.getName());
    assertEquals(
        List.of("name"), heard.stream().map(PropertyChangeEvent::getPropertyName).toList());
  }

  // Sets the email and reads it back, but never listens: dropping its notification goes unseen.
  @Test
  void anEmailIsKept() {
    var person = new Person();

    person.setEmail("ada@example.com");

    assertEquals("ada@example.com", person.getEmail());
  }

  @Test
  void renamingJoinsTheParts() {
    var person = new Person();

    person.rename("Ada", "Lovelace");

    assertEquals("Ada Lovelace", person.getName());
  }
}
