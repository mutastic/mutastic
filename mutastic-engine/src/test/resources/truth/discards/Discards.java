package discards;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/// One method for each kind of call whose result is thrown away. Each call is a statement on a
/// line of its own, so the card's claim, the call removed, can be written by taking the line out.
@SuppressWarnings({"unused", "ReturnValueIgnored", "CheckReturnValue"})
final class Discards {
  private Discards() {}

  static String added(int a, int b) {
    var items = new ArrayList<Integer>();
    items.add(a);
    items.add(b);
    return items.toString();
  }

  static String appended(int a, int b) {
    var text = new StringBuilder();
    text.append(a);
    text.append(',');
    return text.append(b).toString();
  }

  static String put(int a, int b) {
    Map<Integer, Integer> seen = new HashMap<>();
    seen.put(a, b);
    seen.put(b, a);
    return new java.util.TreeMap<>(seen).toString();
  }

  static String removed(int a, int b) {
    var items = new TreeSet<>(List.of(-4, 0, 3, 5));
    items.remove(a);
    return items.toString();
  }

  static long counted(int a, int b) {
    var count = new AtomicLong(a);
    count.incrementAndGet();
    count.addAndGet(b);
    return count.get();
  }

  static String parsed(int a, int b) {
    var text = a < 0 ? "x" : Integer.toString(a);
    Integer.parseInt(text);
    return text;
  }

  static int checked(int a, int b) {
    var maybe = a > 0 ? Optional.of(a) : Optional.<Integer>empty();
    maybe.orElseThrow();
    return a + b;
  }

  static String required(int a, int b) {
    var text = a > 0 ? "x" : null;
    Objects.requireNonNull(text);
    return String.valueOf(text);
  }

  static String used(int a, int b) {
    var items = new ArrayList<Integer>();
    boolean changed = items.add(a);
    return changed + "" + items;
  }
}
