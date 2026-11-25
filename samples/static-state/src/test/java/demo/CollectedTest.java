package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Collects what it saw in a static final list, never cleared.
class CollectedTest {
  private static final List<Integer> SEEN = new ArrayList<>();

  @Test
  void seesOnePrice() {
    SEEN.add(Shop.collected(200));
    assertEquals(List.of(190), SEEN);
  }
}
