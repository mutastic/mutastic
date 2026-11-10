package discards;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/// Calls and assignments taken out whose parts still run: Java works out what a call is on and its
/// arguments before it calls, and an assignment's value before it stores it, so a card shows what's
/// left as statements of their own. Compiled with those statements, each must do what its mutant
/// does.
@SuppressWarnings({"unused", "ReturnValueIgnored", "CheckReturnValue", "UnusedAssignment"})
final class Leftovers {
  private Leftovers() {}

  static String anArgument(int a, int b) {
    var seen = new AtomicInteger(a);
    var items = new ArrayList<Integer>();
    items.add(seen.addAndGet(b));
    return items + " " + seen;
  }

  static String aReceiver(int a, int b) {
    var made = new ArrayList<List<Integer>>();
    listIn(made).add(a);
    return made.toString();
  }

  static int aVoidCallsArgument(int a, int b) {
    var seen = new AtomicInteger(a);
    seen.lazySet(seen.getAndAdd(b));
    return seen.get();
  }

  static String afterAComment(int a, int b) {
    var seen = new AtomicInteger(a);
    var items = new ArrayList<Integer>();
    items.add(
        /* the sum */ seen.addAndGet(b));
    return items + " " + seen;
  }

  static String inAConditional(int a, int b) {
    var items = new ArrayList<Integer>();
    items.add(a > b ?
        a :
        b > 0 ?
        1 :
        b);
    return items.toString();
  }

  static int aBracketedValue(int a, int b) {
    var seen = new AtomicInteger(a);
    int total = b;
    total = (seen.incrementAndGet());
    return total + seen.get() * 7;
  }

  private static List<Integer> listIn(List<List<Integer>> made) {
    var list = new ArrayList<Integer>();
    made.add(list);
    return list;
  }
}
