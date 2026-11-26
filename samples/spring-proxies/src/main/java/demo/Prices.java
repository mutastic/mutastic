package demo;

import org.springframework.transaction.annotation.Transactional;

/// What an order costs: its total, with a tenth off from 100.
@Transactional
public class Prices {
  public int total(int each, int count) {
    int total = each * count;
    return total >= 100 ? total - total / 10 : total;
  }
}
