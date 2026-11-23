package io.mutastic.worker;

import java.util.List;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/// The single test methods a run leaves out (`--exclude-tests=PricingTest#bigOrders`), each by its
/// class's name or full name and the method's: every overload of it, and every row a parameterized
/// test makes of it, as each has the method for its source. A class left out whole never reaches
/// the worker.
///
/// @param methods `PricingTest#bigOrders` or `com.example.PricingTest#bigOrders`
record LeftOutMethods(List<String> methods) implements PostDiscoveryFilter {
  LeftOutMethods {
    methods = List.copyOf(methods);
  }

  @Override
  public FilterResult apply(TestDescriptor test) {
    var method =
        test.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
    return method.isPresent()
            && leavesOut(
                Renamer.original(method.get().getClassName()), method.get().getMethodName())
        ? FilterResult.excluded("left out by --exclude-tests")
        : FilterResult.included("not left out");
  }

  /// Whether the method `name` of the class `className` (`com.example.PricingTest`) is left out.
  boolean leavesOut(String className, String name) {
    var simpleName = className.substring(className.lastIndexOf('.') + 1);
    for (String each : methods) {
      int hash = each.indexOf('#');
      var testClass = each.substring(0, hash);
      if (each.substring(hash + 1).equals(name)
          && (testClass.equals(className) || testClass.equals(simpleName))) {
        return true;
      }
    }
    return false;
  }
}
