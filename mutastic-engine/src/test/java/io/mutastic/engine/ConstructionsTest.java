package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// How a covering test makes a suggested call's receiver, read from its source: its own method's
/// first, then one the class makes outside its tests, and only whole numbers written out.
class ConstructionsTest {
  @TempDir Path tests;

  private void write(String text) throws Exception {
    var file = tests.resolve("com/example/DeliveryTest.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void aTestsOwnMethodMakesTheReceiver() throws Exception {
    write(
        """
        class DeliveryTest {
          @Test
          void other() {
            var delivery = new Delivery(70);
          }

          @Test
          void smallOrders() {
            var delivery = new Delivery(50);
            assertFalse(delivery.isFree(20));
          }
        }
        """);

    var built =
        Constructions.in(
            List.of(tests), List.of("com.example.DeliveryTest#smallOrders"), "Delivery", 1);

    assertThat(built)
        .contains(
            new Constructions.Built("DeliveryTest#smallOrders", List.of(50L), "new Delivery(50)"));
  }

  @Test
  void aReceiverTheClassMakesOnceIsTheClasss() throws Exception {
    write(
        """
        class DeliveryTest {
          private final Delivery delivery = new Delivery(1_000L);

          @Test
          void smallOrders(java.nio.file.Path dir) {
            assertFalse(delivery.isFree(20));
          }
        }
        """);

    assertThat(
            Constructions.in(
                List.of(tests),
                List.of("com.example.DeliveryTest#smallOrders(java.nio.file.Path)"),
                "Delivery",
                1))
        .contains(new Constructions.Built("DeliveryTest", List.of(1000L), "new Delivery(1000)"));
  }

  @Test
  void aReceiverMadeOfAnythingButWholeNumbersIsNotOne() throws Exception {
    write(
        """
        class DeliveryTest {
          @Test
          void smallOrders() {
            var delivery = new Delivery(LIMIT);
            var other = new Delivery(50, 2);
            var third = new Delivery(50);
            var fourth = new Delivery(60);
          }
        }
        """);

    assertThat(
            Constructions.in(
                List.of(tests), List.of("com.example.DeliveryTest#smallOrders"), "Delivery", 1))
        .map(Constructions.Built::arguments)
        .contains(List.of(50L));
    assertThat(
            Constructions.in(
                List.of(tests), List.of("com.example.DeliveryTest#smallOrders"), "Window", 1))
        .isEmpty();
    assertThat(Constructions.in(List.of(tests), List.of("com.example.Gone#a"), "Delivery", 1))
        .isEmpty();
  }
}
