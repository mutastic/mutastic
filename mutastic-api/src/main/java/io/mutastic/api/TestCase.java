package io.mutastic.api;

/// One case of a test that runs a table of them, as it ran: an invocation of a parameterized or
/// repeated test, or a dynamic test. The test itself, which mutastic selects, runs again and names,
/// is still its method; the case is evidence of what failed in this run.
///
/// @param index its position among its method's cases in this run, from 1. A case added before it
///   moves it, so it says which case failed this time, not a name to keep.
/// @param name what the framework calls it: `[2] 2, 3, 5` for a parameterized test's, by default
@Internal
public record TestCase(int index, String name) {}
