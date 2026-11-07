package io.mutastic.report;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.ClassModel;
import java.util.List;
import java.util.Optional;

/// An operator on a class as a whole, for what the reports say of its mutants.
record WholeClassStub(Reach reach) implements ClassMutator {
  @Override
  public String id() {
    return "stub/whole";
  }

  @Override
  public String explanation() {
    return "stub";
  }

  @Override
  public List<Change> changes(ClassModel owner) {
    return List.of();
  }

  @Override
  public byte[] write(ClassModel owner, Member member, String label) {
    return new byte[0];
  }

  @Override
  public Mutator.Words words(Mutant mutant) {
    return new Mutator.Words(
        "@Size removed", "no test needs `@Size`", "add a test `@Size` refuses");
  }

  /// A mutant of it on `member`, in `com.example.Pricing`, on line `line`.
  Mutant mutant(Member member, int line) {
    return new Mutant(
        "com/example/Pricing",
        member.name(),
        member.descriptor(),
        0,
        line,
        this,
        "@Size",
        "remove",
        Optional.empty());
  }
}
