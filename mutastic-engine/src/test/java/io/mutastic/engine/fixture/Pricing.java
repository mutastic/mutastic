package io.mutastic.engine.fixture;

import java.util.ArrayList;
import java.util.List;

public class Pricing {
  private final List<String> audit = new ArrayList<>();

  public int discount(int total) {
    if (total >= 100) {
      return total / 10;
    }
    return 0;
  }

  public long total(long price, int quantity) {
    return price * quantity + 5;
  }

  public boolean isFree(String code) {
    return code != null && code.equals("FREE");
  }

  public void checkout(String order) {
    record(order);
    System.out.println("checked out " + order);
  }

  public void record(String event) {
    audit.add(event);
  }

  public List<String> audit() {
    return audit;
  }

  public int withLock(java.util.concurrent.locks.ReentrantLock lock, java.util.function.IntSupplier action) {
    lock.lock();
    try {
      return action.getAsInt();
    } finally {
      lock.unlock();
    }
  }

  public void clearTwice(List<String> items) {
    items.clear(); items.clear();
  }

  public enum Tier { GOLD, SILVER }

  public record Line(String item, int price) {}
}
