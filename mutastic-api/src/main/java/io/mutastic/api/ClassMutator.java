package io.mutastic.api;

import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.List;
import java.util.Optional;

/// An operator a plugin adds that changes a class as a whole, where a [Mutator] changes one
/// instruction: what no instruction holds, as an annotation on a class or a field, or a method
/// written anew. Plugins provide theirs as a [java.util.ServiceLoader] service, in a jar
/// `mutastic.yaml` lists under `plugins:`.
///
/// A mutant of one runs with every class loaded anew, as what reads an annotation may have kept
/// what it read, and a class written anew may have methods the build's doesn't: swapping it into
/// the class already loaded wouldn't reach either. That costs a load of the classes, and whatever a
/// test sets up on them, for each mutant.
///
/// Each mutant's id is made from its member, its change's label and the operator's id, so a label
/// must never change once released.
public non-sealed interface ClassMutator extends Operator {
  /// The changes this operator makes in `owner`, each by its member and label; none when it doesn't
  /// apply.
  List<Change> changes(ClassModel owner);

  /// `owner`'s bytes with the change made. A change that doesn't verify, or that throws an
  /// [IllegalArgumentException] or [IllegalStateException] here, is skipped as unviable, never run.
  byte[] write(ClassModel owner, Member member, String label);

  /// What a missed mutant's card says.
  Mutator.Words words(Mutant mutant);

  /// Told of the project before any of its classes, for an operator that needs more than the class
  /// file: to compile against its classpath, or read its sources.
  default void prepare(Project project) {}

  /// Which tests a mutant runs against.
  default Reach reach() {
    return Reach.EVERY_TEST;
  }

  @Override
  default boolean onCalls() {
    return false;
  }

  @Override
  default Tier tier() {
    return Tier.EXTENDED;
  }

  /// Which tests can notice a change. Coverage says which tests reach a line, and a change with no
  /// line needs another way to say it.
  enum Reach {
    /// Every test: those that reach the class first, each set fastest first, stopping at the first
    /// failure. For what's read by reflection: a test that a method's `@PreAuthorize` denies never
    /// runs a line of it, and the class was made while a framework started up, in no test. A mutant
    /// no test notices costs a run of every test.
    EVERY_TEST,

    /// The tests that reach a line of the mutant's method, fastest first; none, and it's not
    /// covered. For a method that changes what it does when it runs.
    METHOD
  }

  /// What a change is on: a method, a field or the class itself. Its name and descriptor stand in
  /// for a mutant's method, in its id and in what reports show.
  ///
  /// @param name the method's name, `<field total>`, or `<class>`
  /// @param descriptor the method's or the field's descriptor; empty for the class
  record Member(String name, String descriptor) {
    private static final String FIELD_PREFIX = "<field ";

    /// The class itself.
    public static Member ofClass() {
      return new Member("<class>", "");
    }

    public static Member ofField(String name, String descriptor) {
      return new Member(FIELD_PREFIX + name + ">", descriptor);
    }

    public static Member ofMethod(MethodModel method) {
      return new Member(method.methodName().stringValue(), method.methodType().stringValue());
    }

    public boolean isClass() {
      return name.equals("<class>");
    }

    public boolean isField() {
      return name.startsWith(FIELD_PREFIX);
    }

    public boolean isMethod() {
      return !isClass() && !isField();
    }

    /// The field's name, for a field; the method's, for a method; empty for the class.
    public String simpleName() {
      if (isField()) {
        return name.substring(FIELD_PREFIX.length(), name.length() - 1);
      }
      return isClass() ? "" : name;
    }
  }

  /// One change to a class.
  ///
  /// @param member what it's on
  /// @param label what it becomes, short: `remove @NotNull`
  /// @param original what's there now: `@NotNull`
  /// @param sourceText the text that marks its line in the source, `@NotNull`, for the line a card
  ///   shows: class files have no line for an annotation. Empty when nothing does
  /// @param skipped why it shouldn't run, if it shouldn't, kind first: `equivalent: …`
  record Change(
      Member member, String label, String original, String sourceText, Optional<String> skipped) {
    public static Change of(Member member, String label, String original, String sourceText) {
      return new Change(member, label, original, sourceText, Optional.empty());
    }
  }
}
