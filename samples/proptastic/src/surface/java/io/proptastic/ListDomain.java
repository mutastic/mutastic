package io.proptastic;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

/// Lists of up to 20 values, shrunk by taking values out, then by shrinking each.
public final class ListDomain<T> extends Domain<List<T>> {
  private static final int LONGEST = 20;

  private final Domain<T> elements;

  ListDomain(Domain<T> elements) {
    this.elements = elements;
  }

  @Override
  List<T> draw(SplittableRandom random) {
    int size = random.nextInt(LONGEST + 1);
    var out = new ArrayList<T>(size);
    for (int i = 0; i < size; i++) {
      out.add(elements.draw(random));
    }
    return List.copyOf(out);
  }

  @Override
  List<List<T>> smaller(List<T> value) {
    var out = new ArrayList<List<T>>();
    if (!value.isEmpty()) {
      out.add(List.of());
    }
    for (int i = 0; i < value.size(); i++) {
      var without = new ArrayList<>(value);
      without.remove(i);
      out.add(List.copyOf(without));
    }
    for (int i = 0; i < value.size(); i++) {
      for (var smaller : elements.smaller(value.get(i))) {
        var changed = new ArrayList<>(value);
        changed.set(i, smaller);
        out.add(List.copyOf(changed));
      }
    }
    return out;
  }

  @Override
  String shown(List<T> value) {
    return value.stream().map(elements::shown).collect(Collectors.joining(", ", "[", "]"));
  }

  @Override
  String source(List<T> value) {
    return value.stream().map(elements::source).collect(Collectors.joining(", ", "List.of(", ")"));
  }
}
