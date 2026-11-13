package io.mutastic.worker;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/// Finds a test that would catch a missed mutant by running it: calls the method as it is today and
/// with the mutant swapped in, on candidate inputs, and keeps the first input where they disagree.
/// The expected value is what the code does today, which the person must check is what they meant.
/// A method that gives different answers to the same call gets no suggestion.
///
/// Only methods whose parameters are all whole numbers (`int`, `long`, `Integer`, `Long`), static
/// or on an instance the engine says how to make (a constructor whose parameters are whole numbers
/// too, or none; for an inner class, its outer class's constructor first), returning a primitive, a
/// `String` or a boxed primitive: values that can be written in a test as literals.
final class Suggester {
  /// How long one call may take before it's taken as one that doesn't return. A call that returns
  /// at once can still take a few hundred milliseconds on a busy machine (a class just swapped in,
  /// a pause to collect garbage): with a limit that tight, a card said a mutant "doesn't return"
  /// when it returned, and which card a run gave depended on the machine. A call that really
  /// doesn't return costs this once, as the worker is replaced after it.
  private static final long CALL_MILLIS = 2_000;

  /// How long the calls as the code is today may take together, for one mutant.
  private static final long BUDGET_MILLIS = 1000;

  /// What was found. `stuck` means a call on the mutant didn't return: its thread is still running,
  /// so the worker should be replaced.
  record Found(Optional<List<Long>> inputs, String today, String mutated, boolean stuck) {}

  /// The method a suggestion is for. `receiver` is `-` for a static method, else the descriptor of
  /// the constructor to make the receiver with. An inner class's receiver is made inside an
  /// instance of its outer class, made first: the descriptors outermost first, joined by `>`.
  record Target(String className, String methodName, String descriptor, String receiver) {
    boolean isStatic() {
      return receiver.equals("-");
    }
  }

  private static final Found NONE = new Found(Optional.empty(), "", "", false);

  private static final Set<Class<?>> WRITABLE_BOXES =
      Set.of(
          Integer.class,
          Long.class,
          Boolean.class,
          Short.class,
          Byte.class,
          Character.class,
          Double.class,
          Float.class);

  private Suggester() {}

  /// Each candidate holds the receiver's constructor arguments first, outermost first, then the
  /// method's.
  static Found find(
      ProductionLoader production, Target target, byte[] mutantBytes, List<List<Long>> candidates)
      throws Exception {
    var call = callOf(production.loadClass(target.className()), target);
    if (call.isEmpty()) {
      return NONE;
    }
    var today = today(call.get(), candidates);
    if (today.isEmpty()) {
      return NONE;
    }
    production.swapIn(Map.of(target.className(), mutantBytes));
    try {
      return firstDifference(call.get(), candidates, today.get());
    } finally {
      production.swapBack(Set.of(target.className()));
    }
  }

  /// The call to make, or empty if the method or its receiver's constructor can't be written as
  /// literals.
  private static Optional<Call> callOf(Class<?> owner, Target target) {
    var method = method(owner, target.methodName(), target.descriptor(), target.isStatic());
    var receiver =
        target.isStatic()
            ? Optional.of(List.<Constructor<?>>of())
            : receiver(owner, target.receiver());
    if (method.isEmpty() || receiver.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Call(method.get(), receiver.get()));
  }

  /// The constructors that make an instance of `owner`, outermost first: its own, after its outer
  /// class's if it's an inner class. An inner class's constructor takes the outer instance first.
  private static Optional<List<Constructor<?>>> receiver(Class<?> owner, String receiver) {
    var descriptors = receiver.split(">", -1);
    var made = new ArrayList<Constructor<?>>();
    Class<?> type = owner;
    for (int index = descriptors.length - 1; index >= 0; index--) {
      boolean inner = index > 0;
      var constructor = constructor(type, descriptors[index], inner ? 1 : 0);
      if (constructor.isEmpty()) {
        return Optional.empty();
      }
      made.add(0, constructor.get());
      if (inner) {
        type = constructor.get().getParameterTypes()[0];
      }
    }
    return Optional.of(made);
  }

  /// What the method returns today for each candidate tried within the budget, null where it didn't
  /// return in time; empty if it answered one call two ways.
  private static Optional<List<String>> today(Call call, List<List<Long>> candidates) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS);
    var today = new ArrayList<String>();
    int again = AGAIN;
    for (List<Long> inputs : candidates) {
      if (System.nanoTime() > deadline) {
        break;
      }
      var first = call.on(inputs);
      // Again: a method that answers differently to the same call (it uses a random number, the
      // clock, shared state) can't be pinned by an assertion, and any difference the mutant
      // seemed to make could be chance.
      if (first.isPresent() && varies(call, inputs, first, again)) {
        return Optional.empty();
      }
      again = 1;
      today.add(first.orElse(null));
    }
    return Optional.of(today);
  }

  /// How often the first candidate is called again, a millisecond apart: two calls in a row read
  /// the same clock tick, and a clock's answer would pass for a fixed one.
  private static final int AGAIN = 6;

  /// Whether `times` more calls, a millisecond apart when more than one, answer other than `first`.
  private static boolean varies(Call call, List<Long> inputs, Optional<String> first, int times) {
    for (int i = 0; i < times; i++) {
      if (times > 1) {
        pause();
      }
      if (!first.equals(call.on(inputs))) {
        return true;
      }
    }
    return false;
  }

  private static void pause() {
    try {
      Thread.sleep(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// The first candidate where the mutant, now swapped in, answers differently from `today`.
  private static Found firstDifference(Call call, List<List<Long>> candidates, List<String> today) {
    for (int index = 0; index < today.size(); index++) {
      if (today.get(index) == null) {
        continue; // the original didn't return in time: no expected value to write
      }
      var mutated = call.on(candidates.get(index));
      if (mutated.isEmpty()) {
        return new Found(
            Optional.of(candidates.get(index)), today.get(index), "doesn't return", true);
      }
      if (!Objects.equals(today.get(index), mutated.get())) {
        return new Found(
            Optional.of(candidates.get(index)), today.get(index), mutated.get(), false);
      }
    }
    return NONE;
  }

  private static Optional<Method> method(
      Class<?> owner, String name, String descriptor, boolean isStatic) {
    for (Method method : owner.getDeclaredMethods()) {
      var type = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
      if (method.getName().equals(name)
          && type.toMethodDescriptorString().equals(descriptor)
          && wholeNumbers(method.getParameterTypes())
          && Modifier.isStatic(method.getModifiers()) == isStatic
          && writable(method.getReturnType())) {
        method.setAccessible(true);
        return Optional.of(method);
      }
    }
    return Optional.empty();
  }

  /// `owner`'s constructor with `descriptor`, if its parameters after the first `skip` are whole
  /// numbers.
  private static Optional<Constructor<?>> constructor(Class<?> owner, String descriptor, int skip) {
    for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
      var types = constructor.getParameterTypes();
      var type = MethodType.methodType(void.class, types);
      if (type.toMethodDescriptorString().equals(descriptor)
          && types.length >= skip
          && wholeNumbers(Arrays.copyOfRange(types, skip, types.length))) {
        constructor.setAccessible(true);
        return Optional.of(constructor);
      }
    }
    return Optional.empty();
  }

  private static boolean wholeNumbers(Class<?>[] types) {
    return Arrays.stream(types)
        .allMatch(
            type ->
                type == int.class
                    || type == long.class
                    || type == Integer.class
                    || type == Long.class);
  }

  /// One call: the method on a fresh receiver (or none, if static), with its arguments.
  private static final class Call {
    private final Method method;

    /// The constructors that make the receiver, outermost first; none for a static method.
    private final List<Constructor<?>> receiver;

    Call(Method method, List<Constructor<?>> receiver) {
      this.method = method;
      this.receiver = receiver;
    }

    /// `inputs` are the constructors' arguments, outermost first, then the method's.
    Optional<String> on(List<Long> inputs) {
      var receiverArguments = new ArrayList<Object[]>();
      int next = 0;
      for (int index = 0; index < receiver.size(); index++) {
        var types = filled(receiver.get(index), index);
        receiverArguments.add(arguments(types, inputs.subList(next, next + types.length)));
        next += types.length;
      }
      var methodArguments =
          arguments(method.getParameterTypes(), inputs.subList(next, inputs.size()));
      return call(method, () -> make(receiverArguments), methodArguments);
    }

    /// The parameters of the receiver's constructor at `index` that inputs fill: all of them but an
    /// inner class's outer instance.
    private static Class<?>[] filled(Constructor<?> constructor, int index) {
      var types = constructor.getParameterTypes();
      return index == 0 ? types : Arrays.copyOfRange(types, 1, types.length);
    }

    /// A fresh receiver, each instance passed first to the next constructor; null for a static
    /// method.
    private @Nullable Object make(List<Object[]> receiverArguments) throws Exception {
      if (receiver.isEmpty()) {
        return null;
      }
      Object made = receiver.get(0).newInstance(receiverArguments.get(0));
      for (int index = 1; index < receiver.size(); index++) {
        var own = receiverArguments.get(index);
        var all = new Object[own.length + 1];
        all[0] = made;
        System.arraycopy(own, 0, all, 1, own.length);
        made = receiver.get(index).newInstance(all);
      }
      return made;
    }
  }

  /// Makes the receiver for a call; may throw, as a constructor may.
  private interface Receiver {
    /// The receiver, or null for a static method.
    @Nullable Object make() throws Exception;
  }

  private static Object[] arguments(Class<?>[] types, List<Long> values) {
    var arguments = new Object[values.size()];
    for (int index = 0; index < values.size(); index++) {
      long value = values.get(index);
      arguments[index] =
          types[index] == int.class || types[index] == Integer.class
              ? (Object) (int) value
              : (Object) value;
    }
    return arguments;
  }

  /// The call's result as Java source (`10`, `10L`, `true`, `"text"`, `null`), `throws
  /// IllegalStateException`, or empty if it didn't return in time. A fresh instance per call, so
  /// one call's state can't change another's result.
  private static Optional<String> call(Method method, Receiver receiver, Object[] arguments) {
    // A daemon thread rather than an executor, whose close() would wait forever on a loop.
    var task = new FutureTask<>(() -> method.invoke(receiver.make(), arguments));
    var thread = new Thread(task, "mutastic-suggestion");
    thread.setDaemon(true);
    thread.start();
    try {
      return Optional.of(literal(task.get(CALL_MILLIS, TimeUnit.MILLISECONDS)));
    } catch (ExecutionException e) {
      var cause =
          e.getCause() instanceof InvocationTargetException invoked
              ? invoked.getCause()
              : e.getCause();
      var thrown = cause == null ? e : cause; // an exception without a cause is its own
      return Optional.of("throws " + thrown.getClass().getName());
    } catch (TimeoutException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  static String literal(Object value) {
    // Java 17: no patterns in switch.
    if (value == null) {
      return "null";
    }
    if (value instanceof String text) {
      return stringLiteral(text);
    }
    if (value instanceof Long number) {
      return number + "L";
    }
    if (value instanceof Float number) {
      return number + "f";
    }
    if (value instanceof Character character) {
      return charLiteral(character);
    }
    return String.valueOf(value);
  }

  private static String stringLiteral(String text) {
    return '"'
        + text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + '"'; // and so never a tab, which the protocol splits on
  }

  private static String charLiteral(Character character) {
    return "'"
        + (character == '\'' ? "\\'" : character == '\\' ? "\\\\" : character.toString())
        + "'";
  }

  private static boolean writable(Class<?> type) {
    return type != void.class
        && (type.isPrimitive() || type == String.class || WRITABLE_BOXES.contains(type));
  }
}
