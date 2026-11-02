package com.example.nested;

import java.util.Comparator;
import java.util.List;

/// Shipping costs, in every kind of class javac makes from one file. Each kind has one change its
/// tests don't notice.
public class Shipping {
  private final int heavyFrom;

  public Shipping(int heavyFrom) {
    this.heavyFrom = heavyFrom;
  }

  /// A static nested class: `Shipping$Zone`.
  public static final class Zone {
    private final int distance;

    public Zone(int distance) {
      this.distance = distance;
    }

    /// 3 from 100 km on; the tests never ship exactly 100 km.
    public int surcharge() {
      if (distance >= 100) {
        return 3;
      }
      return 0;
    }

    @Override
    public String toString() {
      return distance > 0 ? "zone " + distance : "local";
    }
  }

  /// An inner class, reading the outer instance's limit: `Shipping$Parcel`.
  public final class Parcel {
    private final int weight;

    public Parcel(int weight) {
      this.weight = weight;
    }

    /// The tests never weigh a parcel at exactly the limit.
    public boolean heavy() {
      return weight >= heavyFrom;
    }
  }

  /// An anonymous class: `Shipping$1`. Its tests only check that the order is sorted somehow.
  public Comparator<Integer> cheapestFirst() {
    return new Comparator<>() {
      @Override
      public int compare(Integer left, Integer right) {
        return left - right;
      }
    };
  }

  /// A local class: `Shipping$1Sum`.
  public int total(List<Integer> weights) {
    class Sum {
      int of(List<Integer> all) {
        int sum = 0;
        for (int weight : all) {
          sum = sum + weight;
        }
        return sum;
      }
    }
    return new Sum().of(weights);
  }

  /// A lambda: `lambda$heavyOnes$0`. The tests never include a parcel at exactly the limit.
  public long heavyOnes(List<Integer> weights) {
    return weights.stream().filter(weight -> weight >= heavyFrom).count();
  }

  /// Enum constants with bodies of their own: `Shipping$Speed$1`, `Shipping$Speed$2`.
  public enum Speed {
    STANDARD {
      @Override
      public int days() {
        return 5;
      }
    },
    EXPRESS {
      @Override
      public int days() {
        return 1;
      }
    };

    public abstract int days();
  }
}
