package io.mutastic.engine.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/// A small fluent type, as a library's would be, for call rules that rewrite its calls.
public class Pipeline {
  private final List<Integer> items;

  public Pipeline(List<Integer> items) {
    this.items = List.copyOf(items);
  }

  public Pipeline first(long count) {
    return new Pipeline(items.subList(0, (int) Math.min(count, items.size())));
  }

  public Pipeline map(Function<Integer, Integer> each) {
    var mapped = new ArrayList<Integer>();
    items.forEach(item -> mapped.add(each.apply(item)));
    return new Pipeline(mapped);
  }

  public List<Integer> items() {
    return items;
  }

  /// The first two, doubled.
  public static List<Integer> topTwoDoubled(List<Integer> items) {
    return new Pipeline(items).first(2).map(item -> item * 2).items();
  }
}
