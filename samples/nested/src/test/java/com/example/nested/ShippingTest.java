package com.example.nested;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// The tests are nested too: `@Nested` classes and an anonymous class, in the same package as the
/// code, so mutastic renames them, and their nested classes, each run.
class ShippingTest {
  private final Shipping shipping = new Shipping(10);

  @Nested
  class Zones {
    @Test
    void farIsSurcharged() {
      assertEquals(3, new Shipping.Zone(250).surcharge());
      assertEquals(0, new Shipping.Zone(20).surcharge());
    }
  }

  @Nested
  class Parcels {
    @Test
    void heavyIsWellOverTheLimit() {
      assertTrue(shipping.new Parcel(30).heavy());
      assertFalse(shipping.new Parcel(2).heavy());
    }
  }

  @Test
  void cheapestComesFirstSomehow() {
    var prices = new ArrayList<>(List.of(3, 1, 2));
    prices.sort(shipping.cheapestFirst());
    assertEquals(3, prices.size());
  }

  @Test
  void totalAddsTheWeights() {
    assertEquals(6, shipping.total(List.of(1, 2, 3)));
  }

  @Test
  void heavyOnesAreCountedWellOverTheLimit() {
    assertEquals(1, shipping.heavyOnes(List.of(2, 30)));
  }

  /// An anonymous class in a test: renamed with it.
  @Test
  void expressIsFast() {
    IntSupplier days =
        new IntSupplier() {
          @Override
          public int getAsInt() {
            return Shipping.Speed.EXPRESS.days();
          }
        };
    assertEquals(1, days.getAsInt());
  }
}
