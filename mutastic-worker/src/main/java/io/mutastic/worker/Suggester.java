package io.mutastic.worker;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Finds a test that would catch a missed mutant by running it: calls the method as it is today and
/// with the mutant swapped in, on candidate inputs, and keeps the first input where they disagree.
/// The expected value is what the code does today, which the person must check is what they meant.
/// A method that gives different answers to the same call gets no suggestion.
///
/// Only methods whose parameters are all whole numbers (`int`, `long`, `Integer`, `Long`), static
/// or on an instance the engine says how to make (a constructor whose parameters are whole numbers
/// too, or none), returning a primitive, a `String` or a boxed primitive: values that can be
/// written in a test as literals.
final class Suggester {
  /// How long one call may take, and all the calls for one mutant together.
  private static final long CALL_MILLIS = 200;

  private static final long BUDGET_MILLIS = 1000;

  /// What was found. `stuck` means a call on the mutant didn't return: its thread is still running,
  /// so the worker should be replaced.
  record Found(Optional<List<Long>> inputs, String today, String mutated, boolean stuck) {}

  private Suggester() {}

  /// `receiver` is `-` for a static method, else the descriptor of the constructor to make the
  /// receiver with; each candidate holds its arguments first, then the method's.
  static Found find(
      ProductionLoader production,
      String className,
      String methodName,
      String descriptor,
      byte[] mutantBytes,
      List<List<Long>> candidates,
      String receiver)
      throws Exception {
    var none = new Found(Optional.empty(), "", "", false);
    var owner = production.loadClass(className);
    var method = method(owner, methodName, descriptor, receiver.equals("-"));
    var constructor =
        receiver.equals("-") ? Optional.<Constructor<?>>empty() : constructor(owner, receiver);
    if (method.isEmpty() || !receiver.equals("-") && constructor.isEmpty()) {
      return none;
    }
    var call = new Call(method.get(), constructor.orElse(null));
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS);
    var today = new ArrayList<String>();
    for (List<Long> inputs : candidates) {
      if (System.nanoTime() > deadline) {
        break;
      }
      var first = call.on(inputs);
      // Twice: a method that answers differently to the same call (it uses a random number, the
      // clock, shared state) can't be pinned by an assertion, and any difference the mutant
      // seemed to make could be chance.
      if (first.isPresent() && !first.equals(call.on(inputs))) {
        return none;
      }
      today.add(first.orElse(null));
    }
    production.swapIn(Map.of(className, mutantBytes));
    try {
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
      return none;
    } finally {
      production.swapBack(java.util.Set.of(className));
    }
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

  private static Optional<Constructor<?>> constructor(Class<?> owner, String descriptor) {
    for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
      var type = MethodType.methodType(void.class, constructor.getParameterTypes());
      if (type.toMethodDescriptorString().equals(descriptor)
          && wholeNumbers(constructor.getParameterTypes())) {
        constructor.setAccessible(true);
        return Optional.of(constructor);
      }
    }
    return Optional.empty();
  }

  private static boolean wholeNumbers(Class<?>[] types) {
    return java.util.Arrays.stream(types)
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
    private final Constructor<?> constructor;

    Call(Method method, Constructor<?> constructor) {
      this.method = method;
      this.constructor = constructor;
    }

    /// `inputs` are the constructor's arguments, then the method's.
    Optional<String> on(List<Long> inputs) {
      int split = constructor == null ? 0 : constructor.getParameterCount();
      var receiverArguments =
          arguments(
              constructor == null ? new Class<?>[0] : constructor.getParameterTypes(),
              inputs.subList(0, split));
      var methodArguments =
          arguments(method.getParameterTypes(), inputs.subList(split, inputs.size()));
      return call(
          method,
          () -> constructor == null ? null : constructor.newInstance(receiverArguments),
          methodArguments);
    }
  }

  /// Makes the receiver for a call; may throw, as a constructor may.
  private interface Receiver {
    Object make() throws Exception;
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
      var thrown =
          e.getCause() instanceof InvocationTargetException invoked
              ? invoked.getCause()
              : e.getCause();
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
      return '"'
          + text.replace("\\", "\\\\")
              .replace("\"", "\\\"")
              .replace("\n", "\\n")
              .replace("\r", "\\r")
              .replace("\t", "\\t")
          + '"'; // and so never a tab, which the protocol splits on
    }
    if (value instanceof Long number) {
      return number + "L";
    }
    if (value instanceof Float number) {
      return number + "f";
    }
    if (value instanceof Character character) {
      return "'"
          + (character == '\'' ? "\\'" : character == '\\' ? "\\\\" : character.toString())
          + "'";
    }
    return String.valueOf(value);
  }

  private static boolean writable(Class<?> type) {
    return type != void.class
        && (type.isPrimitive()
            || type == String.class
            || type == Integer.class
            || type == Long.class
            || type == Boolean.class
            || type == Short.class
            || type == Byte.class
            || type == Character.class
            || type == Double.class
            || type == Float.class);
  }
}
