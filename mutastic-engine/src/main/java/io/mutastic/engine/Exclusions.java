package io.mutastic.engine;

import io.mutastic.api.Nearest;
import io.mutastic.api.UnmatchedExclusion;
import io.mutastic.worker.TestNames;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/// The tests a run was told to leave out (`--exclude-tests`, `exclude-tests:`), against the tests
/// it has, by [TestNames]'s rule: a test class is left out before its tests reach a worker, and a
/// method, or a class nested in one, by the worker's filter, which says what it left out.
final class Exclusions {
  private final List<String> names;

  Exclusions(List<String> names) {
    this.names = List.copyOf(names);
  }

  /// Whether the run leaves out the top-level test class `className` whole.
  boolean leaveOutClass(String className) {
    return names.stream().anyMatch(name -> TestNames.leavesOutClass(name, className));
  }

  /// Each name that left out nothing: no test class is of it, and the worker left out no test for
  /// it. With the one test a run names that it's close to, when there is one.
  ///
  /// @param testClasses the module's top-level test classes, by full name
  /// @param leftOut the tests the worker left out
  /// @param ran the tests that ran
  List<UnmatchedExclusion> unmatched(
      List<String> testClasses, Collection<String> leftOut, Collection<String> ran) {
    return names.stream()
        .filter(name -> testClasses.stream().noneMatch(cls -> TestNames.leavesOutClass(name, cls)))
        .filter(name -> leftOut.stream().noneMatch(test -> TestNames.leavesOut(name, test)))
        .map(name -> new UnmatchedExclusion(name, Nearest.name(name, named(testClasses, ran))))
        .toList();
  }

  /// Every way a run names these tests and test classes, for a close name to be one of: each by its
  /// full name and its class's name alone, with its parameter types and without.
  private static SequencedSet<String> named(List<String> testClasses, Collection<String> tests) {
    var forms = new LinkedHashSet<String>();
    for (String test : testClasses) {
      forms.add(test);
      forms.add(test.substring(test.lastIndexOf('.') + 1));
    }
    for (String test : tests) {
      if (!TestIds.canRunAgain(test)) {
        continue;
      }
      var withoutTypes = test.replaceFirst("\\(.*$", "");
      for (String form : List.of(test, withoutTypes)) {
        int hash = form.indexOf('#');
        var className = hash < 0 ? form : form.substring(0, hash);
        forms.add(form);
        forms.add(
            className.substring(className.lastIndexOf('.') + 1)
                + form.substring(className.length()));
      }
    }
    return forms;
  }
}
