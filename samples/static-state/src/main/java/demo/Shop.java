package demo;

/// Prices, one method for each test, so each test alone reaches its method's mutants.
public final class Shop {
  private Shop() {}

  public static int counted(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }

  public static int remembered(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }

  public static int collected(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }

  public static int registered(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }

  public static int held(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }

  public static int nested(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }

  public static int shared(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }

  public static int both(int price) {
    if (price > 100) {
      return price - 10;
    }
    return price;
  }
}
