package io.mutastic.api;

import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.Optional;

/// Decides that a method's mutants shouldn't run, and says why. A filter acts only on positive
/// evidence in the bytecode, never on a name alone: one that skips too much makes the score lie,
/// and nobody can tell.
public interface Filter {
  /// `mutastic:trivial-accessors`: how configuration and audits name it.
  String id();

  /// Why `method`'s mutants shouldn't run, if they shouldn't.
  Optional<Skip> skip(ClassModel owner, MethodModel method);
}
