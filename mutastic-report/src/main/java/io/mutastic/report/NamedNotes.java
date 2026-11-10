package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Suggestion;
import java.util.ArrayList;
import java.util.List;

/// What a name a card's call is at the edge of is, and its value where it's known, said beside the
/// inputs seen: `inputs seen: total = 20, 80, with freeFrom = 50 (Delivery's field, from new
/// Delivery(50))`, or `the edge is FREE_FROM (Discounts.FREE_FROM = 100)`. A name with no value
/// beside it leaves the reader to guess where it came from.
final class NamedNotes {
  private static final String SEEN = "inputs seen: ";

  private NamedNotes() {}

  /// The advice with the name the suggested call is at said in its evidence, if it has one.
  static Advice of(Advice advice, MutantResult result) {
    var suggestion = result.suggestion();
    var named = suggestion.flatMap(Suggestion::named).filter(found -> !found.meaning().isEmpty());
    if (named.isEmpty()) {
      return advice;
    }
    var notes = new ArrayList<>(advice.notes());
    if (suggestion.get().builtBy().isPresent() && withValue(notes, named.get().meaning())) {
      noneThere(result, suggestion.get(), named.get()).ifPresent(notes::add);
    } else {
      notes.add("the edge is " + named.get().meaning());
    }
    return new Advice(
        advice.before(),
        advice.after(),
        advice.change(),
        advice.changedTo(),
        advice.headline(),
        notes,
        advice.fix());
  }

  /// The inputs seen with the value the receiver was made with after them; false if no note says
  /// the inputs.
  private static boolean withValue(List<String> notes, String meaning) {
    for (int at = 0; at < notes.size(); at++) {
      if (notes.get(at).startsWith(SEEN)) {
        notes.set(at, notes.get(at) + ", with " + meaning);
        return true;
      }
    }
    return false;
  }

  /// `none has total == freeFrom`, when no input seen is the value the call is at.
  private static java.util.Optional<String> noneThere(
      MutantResult result, Suggestion suggestion, Suggestion.Named named) {
    var sides = named.note().split(" == ", -1);
    int parameter = sides.length == 2 ? suggestion.parameters().indexOf(sides[0]) : -1;
    if (parameter < 0) {
      return java.util.Optional.empty();
    }
    long value = suggestion.inputs().get(parameter);
    var seen = result.inputs().getOrDefault(sides[0], List.of());
    return seen.isEmpty() || seen.contains(value)
        ? java.util.Optional.empty()
        : java.util.Optional.of("none has " + named.note());
  }
}
