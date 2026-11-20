package io.mutastic.engine;

import static java.lang.constant.ConstantDescs.CD_Throwable;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// What a compiler writes to close a try-with-resources resource is skipped as its own, in every
/// shape each writes, and a close the person wrote beside it isn't.
///
/// - javac 11 and later: `truth/resources/Closes.java`, compiled here, for several `--release`s. A
///   modern javac writes its own shape for every release, so javac 7 to 10's can't come from it.
/// - javac 7 and 8, and 9 and 10 with and without `$closeResource`: assembled here, instruction by
///   instruction, as those versions write it (the shapes JaCoCo's filters describe): no such javac
///   is to hand.
/// - ECJ: `ecj/resources/Closes.class`, `truth/resources/Closes.java` compiled by ECJ 3.40.0 with
///   `-17 -g`. After changing that file, compile it again and copy both classes.
class TryWithResourcesTest {
  private static final String REASON = TryWithResources.REASON;

  /// The fixture's methods with a try-with-resources and nothing the person closes.
  private static final List<String> SHAPES =
      List.of(
          "single",
          "fromNew",
          "two",
          "nested",
          "caught",
          "withFinally",
          "nullResource",
          "declaredBefore",
          "exits");

  /// The fixture's closes written by hand, by method: what calls `close()` or `addSuppressed()`.
  private static final Map<String, Integer> BY_HAND =
      Map.of("besideOne", 1, "closedTwice", 2, "byHand", 3);

  private static final ClassDesc READER = ClassDesc.of("java.io.Reader");
  private static final ClassDesc AUTO_CLOSEABLE = ClassDesc.of("java.lang.AutoCloseable");
  private static final MethodTypeDesc CLOSE = MethodTypeDesc.of(CD_void);
  private static final MethodTypeDesc CLOSE_RESOURCE =
      MethodTypeDesc.of(CD_void, CD_Throwable, AUTO_CLOSEABLE);

  @Test
  void javacsCloseIsSkippedAtEveryRelease() throws IOException {
    for (var release : List.of("10", "17", "25")) {
      var found = compiled("-g", "--release", release);
      everyShapesCloseIsSkipped(found, release);
      everyCloseByHandRuns(found, release);
    }
  }

  @Test
  void ecjsCloseIsSkippedAndACloseByHandIsNot() {
    var found = find(resource("/ecj/resources/Closes.class"));
    everyShapesCloseIsSkipped(found, "ECJ");
    everyCloseByHandRuns(found, "ECJ");
    // ECJ's own check, `primary != t`, is its too.
    assertThat(found)
        .filteredOn(mutant -> mutant.original().equals("IF_ACMPEQ"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).contains(REASON));
  }

  /// Without the local variable table, what tells javac 7's shape from the same code by hand is
  /// gone: in a class for Java 10 or before it's taken for javac's, and in a later one it can't be.
  @Test
  void withoutNamesOnlyTheVersionTellsJavac7sShapeFromCodeByHand() throws IOException {
    assertThat(closingIn(compiled("-g:none", "--release", "10"), "byHand"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).contains(REASON));
    assertThat(closingIn(compiled("-g:none", "--release", "17"), "byHand"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
    everyShapesCloseIsSkipped(compiled("-g:none", "--release", "17"), "no names");
  }

  @Test
  void javac7To10sCloseIsSkippedInEveryShape() {
    for (var closing : Closing.values()) {
      for (int exits = 1; exits <= 2; exits++) {
        var found = find(assembled(closing, exits, 52, false));
        var what = closing + " with " + exits + " ways out";
        assertThat(found).as(what).filteredOn(TryWithResourcesTest::closing).isNotEmpty();
        assertThat(found)
            .as(what)
            .filteredOn(mutant -> mutant.skipped().isEmpty())
            .noneMatch(TryWithResourcesTest::closing)
            .noneMatch(TryWithResourcesTest::closingCode)
            .anyMatch(mutant -> mutant.original().equals("IRETURN"));
      }
    }
  }

  @Test
  void javac9sHelperIsCalledAndItsOwnCodeIsNeverMutated() {
    var found = find(assembled(Closing.HELPER, 1, 53, false));

    assertThat(found)
        .filteredOn(mutant -> mutant.original().contains("$closeResource"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).contains(REASON));
    assertThat(found).noneMatch(mutant -> mutant.methodName().equals("$closeResource"));
  }

  /// The same code, with the primary exception in a variable the person named, is theirs: it's
  /// javac 7's expansion written out by hand. So is any in a class javac 11 or later wrote.
  @Test
  void javac7sShapeByHandIsThePersons() {
    for (var closing : List.of(Closing.FULL, Closing.NOT_NULL)) {
      var named = find(assembled(closing, 1, 52, true));
      var later = find(assembled(closing, 1, 55, false));
      for (var found : List.of(named, later)) {
        assertThat(found)
            .as(closing.name())
            .filteredOn(TryWithResourcesTest::closing)
            .isNotEmpty()
            .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
      }
    }
  }

  private static void everyShapesCloseIsSkipped(List<Mutant> found, String compiler) {
    for (var method : SHAPES) {
      var closes = closingIn(found, method);
      assertThat(closes).as(compiler + " " + method).isNotEmpty();
      assertThat(closes)
          .as(compiler + " " + method)
          .allSatisfy(mutant -> assertThat(mutant.skipped()).contains(REASON));
      assertThat(found)
          .as(compiler + " " + method)
          .filteredOn(mutant -> mutant.methodName().equals(method) && mutant.skipped().isEmpty())
          .isNotEmpty()
          .noneMatch(TryWithResourcesTest::closingCode);
    }
  }

  private static void everyCloseByHandRuns(List<Mutant> found, String compiler) {
    BY_HAND.forEach(
        (method, closes) -> {
          var calls =
              closingIn(found, method).stream().filter(mutant -> mutant.skipped().isEmpty());
          assertThat(calls.map(Mutant::line).distinct())
              .as(compiler + " " + method)
              .hasSize(closes);
        });
    // The person's null check in `besideOne`, beside javac's, runs.
    assertThat(found)
        .filteredOn(
            mutant ->
                mutant.methodName().equals("besideOne")
                    && mutant.original().equals("IFNULL")
                    && mutant.skipped().isEmpty())
        .isNotEmpty();
  }

  /// A call that closes a resource or adds what closing threw as suppressed.
  private static boolean closing(Mutant mutant) {
    return Stream.of(".close()V", "addSuppressed", "$closeResource")
        .anyMatch(mutant.original()::contains);
  }

  /// What's around those calls: the null checks, and the exception kept, rethrown and compared.
  private static boolean closingCode(Mutant mutant) {
    return closing(mutant)
        || Stream.of("IFNULL", "IFNONNULL", "IF_ACMPEQ", "ATHROW", "ASTORE")
            .anyMatch(mutant.original()::equals);
  }

  private static List<Mutant> closingIn(List<Mutant> found, String method) {
    Predicate<Mutant> in = mutant -> mutant.methodName().equals(method);
    return found.stream().filter(in.and(TryWithResourcesTest::closing)).toList();
  }

  private static List<Mutant> compiled(String... options) throws IOException {
    var source =
        Javac.Source.read(
            Path.of("src/test/resources/truth/resources/Closes.java"), "resources.Closes");
    var classes = Javac.compile(List.of(source), List.of(), options);
    return find(Objects.requireNonNull(classes.get("resources.Closes")));
  }

  private static List<Mutant> find(byte[] bytes) {
    var policy = Policy.NONE.withOperators(List.of("all"), Policy.NONE.mutators());
    ClassModel model = ClassFile.of().parse(bytes);
    return new Mutations(ClassFile.of(), policy).find(model);
  }

  private static byte[] resource(String name) {
    try (var in =
        Objects.requireNonNull(TryWithResourcesTest.class.getResourceAsStream(name), name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// How javac 7 to 10's `finally` closes the resource.
  private enum Closing {
    /// javac 7 and 8, and 9 and 10 where the resource can be null.
    FULL,
    /// javac 9 and 10 where it comes from `new`.
    NOT_NULL,
    /// javac 9 and 10's `$closeResource`, past the threshold of copies, where it can be null.
    HELPER,
    /// The same, where it comes from `new`.
    HELPER_NOT_NULL
  }

  /// `static int read(Reader in) { try (Reader r = in) { return r.read(); } }` as javac 7 to 10
  /// write it, or with two ways out, `if (r.read() > 0) return 1; return 2;`. The slots: `in` 0,
  /// `r` 1, the primary exception 2, the value returned 3. With `named`, the primary exception is
  /// in the local variable table, as a variable written by hand is.
  private static byte[] assembled(Closing closing, int exits, int major, boolean named) {
    var owner = ClassDesc.of("shapes.Javac" + major);
    return ClassFile.of()
        .build(
            owner,
            type -> {
              type.withVersion(major, 0);
              type.withMethodBody(
                  "read",
                  MethodTypeDesc.of(CD_int, READER),
                  ClassFile.ACC_STATIC,
                  code -> read(code, new Assembly(owner, closing, exits, named)));
              type.withMethodBody(
                  "$closeResource",
                  CLOSE_RESOURCE,
                  ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                  TryWithResourcesTest::closeResource);
            });
  }

  private record Assembly(ClassDesc owner, Closing closing, int exits, boolean named) {}

  private static void read(CodeBuilder code, Assembly how) {
    var start = code.newLabel();
    var end = code.newLabel();
    code.labelBinding(start).aload(0).astore(1).aconst_null().astore(2);
    var named = code.newBoundLabel();
    var bodies = new java.util.ArrayList<Label[]>();
    var body = code.newBoundLabel();
    code.aload(1).invokevirtual(READER, "read", MethodTypeDesc.of(CD_int));
    var second = code.newLabel();
    if (how.exits() == 2) {
      code.ifle(second).iconst_1();
    }
    code.istore(3);
    bodies.add(new Label[] {body, code.newBoundLabel()});
    copyOfFinally(code, how, 6);
    code.iload(3).ireturn();
    if (how.exits() == 2) {
      code.labelBinding(second).iconst_2().istore(3);
      bodies.add(new Label[] {second, code.newBoundLabel()});
      copyOfFinally(code, how, 6);
      code.iload(3).ireturn();
    }
    var rethrow = code.newBoundLabel();
    code.astore(4).aload(4).astore(2).aload(4).athrow();
    var finallyHandler = code.newBoundLabel();
    code.astore(5);
    copyOfFinally(code, how, 7);
    code.aload(5).athrow().labelBinding(end);
    for (var range : bodies) {
      code.exceptionCatch(range[0], range[1], rethrow, CD_Throwable);
      code.exceptionCatchAll(range[0], range[1], finallyHandler);
    }
    code.exceptionCatchAll(rethrow, finallyHandler, finallyHandler);
    code.localVariable(0, "in", READER, start, end).localVariable(1, "r", READER, named, end);
    if (how.named()) {
      code.localVariable(2, "primary", CD_Throwable, named, end);
    }
  }

  /// One copy of the `finally`, with `suppressed` the slot for what closing threw.
  private static void copyOfFinally(CodeBuilder code, Assembly how, int suppressed) {
    var end = code.newLabel();
    if (how.closing() == Closing.FULL || how.closing() == Closing.HELPER) {
      code.aload(1).ifnull(end);
    }
    if (how.closing() == Closing.HELPER || how.closing() == Closing.HELPER_NOT_NULL) {
      code.aload(2).aload(1).invokestatic(how.owner(), "$closeResource", CLOSE_RESOURCE);
    } else {
      var otherwise = code.newLabel();
      code.aload(2).ifnull(otherwise);
      var tried = code.newBoundLabel();
      code.aload(1).invokevirtual(READER, "close", CLOSE);
      var triedEnd = code.newBoundLabel();
      code.goto_(end);
      var handler = code.newBoundLabel();
      code.astore(suppressed).aload(2).aload(suppressed);
      code.invokevirtual(CD_Throwable, "addSuppressed", MethodTypeDesc.of(CD_void, CD_Throwable));
      code.goto_(end).labelBinding(otherwise).aload(1).invokevirtual(READER, "close", CLOSE);
      code.exceptionCatch(tried, triedEnd, handler, CD_Throwable);
    }
    code.labelBinding(end);
  }

  /// javac 9's `$closeResource(Throwable primary, AutoCloseable r)`.
  private static void closeResource(CodeBuilder code) {
    var otherwise = code.newLabel();
    var end = code.newLabel();
    code.aload(0).ifnull(otherwise);
    var tried = code.newBoundLabel();
    code.aload(1).invokeinterface(AUTO_CLOSEABLE, "close", CLOSE);
    var triedEnd = code.newBoundLabel();
    code.goto_(end);
    var handler = code.newBoundLabel();
    code.astore(2).aload(0).aload(2);
    code.invokevirtual(CD_Throwable, "addSuppressed", MethodTypeDesc.of(CD_void, CD_Throwable));
    code.goto_(end)
        .labelBinding(otherwise)
        .aload(1)
        .invokeinterface(AUTO_CLOSEABLE, "close", CLOSE);
    code.labelBinding(end).return_();
    code.exceptionCatch(tried, triedEnd, handler, CD_Throwable);
  }
}
