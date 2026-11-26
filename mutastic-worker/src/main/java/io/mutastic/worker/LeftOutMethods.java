package io.mutastic.worker;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/// The single test methods a run leaves out (`--exclude-tests=PricingTest#bigOrders`), each in any
/// form [TestNames] takes: every overload of it, or the one its parameter types name, and every row
/// a parameterized test makes of it, as each has the method for its source. A class left out whole
/// never reaches the worker. It keeps each test it left out, so the engine can say of a name that
/// left out none.
///
/// @param methods `PricingTest#bigOrders`, `com.example.PricingTest#bigOrders(int)` and the like
/// @param leftOut each test left out, as [TestNames#id] names it, in the order found
record LeftOutMethods(List<String> methods, Set<String> leftOut) implements PostDiscoveryFilter {
  LeftOutMethods(List<String> methods) {
    this(List.copyOf(methods), new LinkedHashSet<>());
  }

  @Override
  public FilterResult apply(TestDescriptor test) {
    var method =
        test.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
    if (method.isEmpty()) {
      return FilterResult.included("not left out");
    }
    var id =
        TestNames.id(
            Renamer.original(method.get().getClassName()),
            method.get().getMethodName(),
            method.get().getMethodParameterTypes());
    if (leavesOut(id)) {
      leftOut.add(id);
      return FilterResult.excluded("left out by --exclude-tests");
    }
    return FilterResult.included("not left out");
  }

  /// Whether the test `id` (`com.example.PricingTest#bigOrders(int)`) is left out.
  boolean leavesOut(String id) {
    return methods.stream().anyMatch(name -> TestNames.leavesOut(name, id));
  }
}
