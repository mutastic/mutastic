package com.example.shipping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PostageTest {
  static Stream<Parcel> parcels() {
    return Stream.of(new Parcel(100, 3), new Parcel(500, 3), new Parcel(501, 7));
  }

  @ParameterizedTest
  @MethodSource("parcels")
  void costsByWeight(Parcel parcel) {
    assertEquals(parcel.cost(), Postage.cost(parcel.grams()));
  }
}
