package demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/// A second context of its own, so each run makes a CGLIB subclass of `Shop` again.
@SpringJUnitConfig(classes = {Shop.class, ShopTest.Extra.class})
class ShopTest {
  @Autowired Prices prices;

  @Test
  void theShopHasPrices() {
    assertNotNull(prices);
  }

  static class Extra {}
}
