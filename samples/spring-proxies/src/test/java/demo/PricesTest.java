package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(Shop.class)
class PricesTest {
  @Autowired Prices prices;

  @Test
  void aSmallOrderIsItsTotal() {
    assertEquals(90, prices.total(30, 3));
  }

  @Test
  void aBigOrderHasATenthOff() {
    assertEquals(108, prices.total(40, 3));
  }
}
