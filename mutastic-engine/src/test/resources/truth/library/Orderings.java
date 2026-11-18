package library;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Orderings {
  static int compareTo(String a, String b) {
    return a.compareTo(b);
  }

  static int compareToClaim(String a, String b) {
    return b.compareTo(a);
  }

  static boolean comesFirst(String a, String b) {
    return a.length() > 1 && a.compareTo(b) < 0;
  }

  static boolean comesFirstClaim(String a, String b) {
    return a.length() > 1 && b.compareTo(a) < 0;
  }

  static int compareToOfBoxedNumbers(String a, String b) {
    return Integer.valueOf(a.length()).compareTo(b.length());
  }

  static int compareToOfBoxedNumbersClaim(String a, String b) {
    return Integer.valueOf(b.length()).compareTo(a.length());
  }

  static int ofAnyComparable(String a, String b) {
    Comparable<String> first = a;
    return first.compareTo(b);
  }

  static int ofAnyComparableClaim(String a, String b) {
    Comparable<String> second = b;
    return second.compareTo(a);
  }

  static int inAComparator(String a, String b) {
    Comparator<String> byLength = (x, y) -> Integer.valueOf(x.length()).compareTo(y.length());
    return byLength.compare(a, b);
  }

  static int inAComparatorClaim(String a, String b) {
    return Integer.valueOf(b.length()).compareTo(a.length());
  }

  static String reversedComparator(String a, String b) {
    return Stream.of(a, b).sorted(Comparator.<String>naturalOrder().reversed()).toList().toString();
  }

  static String reversedComparatorClaim(String a, String b) {
    return Stream.of(a, b).sorted(Comparator.<String>naturalOrder()).toList().toString();
  }

  static String reversedComparing(String a, String b) {
    var byLength = Comparator.comparing(String::length).reversed();
    return Stream.of(a, b).sorted(byLength).toList().toString();
  }

  static String reversedComparingClaim(String a, String b) {
    var byLength = Comparator.comparing(String::length);
    return Stream.of(a, b).sorted(byLength).toList().toString();
  }

  static String reversedList(String a, String b) {
    return List.of(a, b).reversed().toString();
  }

  static String reversedListClaim(String a, String b) {
    return List.of(a, b).toString();
  }

  static String reversedDeque(String a, String b) {
    Deque<String> both = new ArrayDeque<>(List.of(a, b));
    return both.reversed().peekFirst();
  }

  static String reversedDequeClaim(String a, String b) {
    Deque<String> both = new ArrayDeque<>(List.of(a, b));
    return both.peekFirst();
  }

  static String reverseOrderOf(String a, String b) {
    var both = new ArrayList<>(List.of(a, b));
    both.sort(Collections.reverseOrder(Comparator.comparing(String::length)));
    return both.toString();
  }

  static String reverseOrderOfClaim(String a, String b) {
    var both = new ArrayList<>(List.of(a, b));
    both.sort(Comparator.comparing(String::length));
    return both.toString();
  }

  static String reverseOrder(String a, String b) {
    return Stream.of(a, b).sorted(Comparator.reverseOrder()).toList().toString();
  }

  static String reverseOrderClaim(String a, String b) {
    return Stream.of(a, b).sorted(Comparator.naturalOrder()).toList().toString();
  }

  static String reverseOrderOfCollections(String a, String b) {
    var both = new ArrayList<>(List.of(a, b));
    both.sort(Collections.reverseOrder());
    return both.toString();
  }

  static String reverseOrderOfCollectionsClaim(String a, String b) {
    var both = new ArrayList<>(List.of(a, b));
    both.sort(Comparator.naturalOrder());
    return both.toString();
  }

  // Can't change the result: the two are 0 for the same values.

  static boolean same(String a, String b) {
    return a.compareTo(b) == 0;
  }

  static boolean different(String a, String b) {
    return a.compareTo(b) != 0;
  }

  // Not swapped: the argument is of a supertype, which may have no `compareTo` of that type.

  static int aDateComparedWithASupertype(String a, String b) {
    return LocalDate.of(2000, 1, 1 + a.length()).compareTo(LocalDate.of(2000, 1, 1 + b.length()));
  }
}
