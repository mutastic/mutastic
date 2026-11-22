package cleanup;

import java.lang.ref.Cleaner;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/// Each method's call that releases something, and as `xClaim` the test its card says catches the
/// call taken away: `true` when that test passes.
@SuppressWarnings({"unused", "try"})
class Cleanups {
  /// A resource that says whether it's closed. "fail" makes its work throw.
  static final class Res implements AutoCloseable {
    boolean closed;

    void work(String what) {
      if (what.equals("fail")) {
        throw new IllegalStateException(what);
      }
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /// Whether `m` closed the resource it was given, after `what` work.
  private interface Use {
    void accept(Res resource, String what);
  }

  private static boolean closedAfter(Use use, String what) {
    var resource = new Res();
    try {
      use.accept(resource, what);
    } catch (IllegalStateException expected) {
      // the failure path
    }
    return resource.closed;
  }

  // close() in a finally: closed when the method returns, and when it throws.

  static void inFinally(Res resource, String what) {
    try {
      resource.work(what);
    } finally {
      resource.close();
    }
  }

  static boolean inFinallyClaim(String a, String b) {
    return closedAfter(Cleanups::inFinally, "ok") && closedAfter(Cleanups::inFinally, "fail");
  }

  /// Through the interface, and with a `catch` before the `finally`.
  static void throughTheInterface(AutoCloseable resource, String what) throws Exception {
    try {
      ((Res) resource).work(what);
    } catch (IllegalArgumentException e) {
      throw e;
    } finally {
      resource.close();
    }
  }

  static boolean throughTheInterfaceClaim(String a, String b) {
    Use use =
        (resource, what) -> {
          try {
            throughTheInterface(resource, what);
          } catch (IllegalStateException e) {
            throw e;
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    return closedAfter(use, "ok") && closedAfter(use, "fail");
  }

  // close() on one way through: in a catch, only a failure reaches it.

  static Res inCatch(Res resource, String what) {
    try {
      resource.work(what);
      return resource;
    } catch (IllegalStateException e) {
      resource.close();
      throw e;
    }
  }

  static boolean inCatchClaim(String a, String b) {
    return closedAfter(Cleanups::inCatch, "fail");
  }

  static void whenDone(Res resource, String what) {
    resource.work(what);
    if (what.equals("done")) {
      resource.close();
    }
  }

  static boolean whenDoneClaim(String a, String b) {
    return closedAfter(Cleanups::whenDone, "done");
  }

  static void inALambda(Res resource, String what) {
    Runnable closing = () -> resource.close();
    resource.work(what);
    closing.run();
  }

  static boolean inALambdaClaim(String a, String b) {
    return closedAfter(Cleanups::inALambda, "ok");
  }

  // closeQuietly: as for close().

  private static void closeQuietly(AutoCloseable resource) {
    try {
      resource.close();
    } catch (Exception ignored) {
      // quietly
    }
  }

  static void quietly(Res resource, String what) {
    try {
      resource.work(what);
    } finally {
      closeQuietly(resource);
    }
  }

  static boolean quietlyClaim(String a, String b) {
    return closedAfter(Cleanups::quietly, "ok");
  }

  // JDBC: a connection from a source passed in, checked on a mock.

  /// A mock of `type` that records each method called on it, and answers with `answer`.
  private static <T> T mock(Class<T> type, Set<String> called, Object answer) {
    return type.cast(
        Proxy.newProxyInstance(
            Cleanups.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) -> {
              called.add(method.getName());
              return method.getReturnType() == boolean.class ? Boolean.TRUE : answer;
            }));
  }

  static boolean valid(Supplier<Connection> source) throws SQLException {
    var connection = source.get();
    try {
      return connection.isValid(1);
    } finally {
      connection.close();
    }
  }

  static boolean validClaim(String a, String b) throws SQLException {
    var called = new HashSet<String>();
    valid(() -> mock(Connection.class, called, null));
    return called.contains("close");
  }

  static void executed(Connection connection, String sql) throws SQLException {
    var statement = connection.createStatement();
    try {
      statement.execute(sql);
    } finally {
      statement.close();
    }
  }

  static boolean executedClaim(String a, String b) throws SQLException {
    var called = new HashSet<String>();
    var statement = mock(Statement.class, called, null);
    executed(mock(Connection.class, new HashSet<>(), statement), "select 1");
    return called.contains("close");
  }

  // A pool's shutdown: pass in the pool, or a factory for it, and assert isShutdown().

  static String ranOn(Supplier<ExecutorService> pools) throws Exception {
    var pool = pools.get();
    try {
      return pool.submit(() -> "done").get();
    } finally {
      pool.shutdown();
    }
  }

  static boolean ranOnClaim(String a, String b) throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    ranOn(() -> pool);
    var shut = pool.isShutdown();
    pool.shutdownNow();
    return shut;
  }

  static void stoppedNow(ExecutorService pool) {
    pool.shutdownNow();
  }

  static boolean stoppedNowClaim(String a, String b) {
    var pool = Executors.newSingleThreadExecutor();
    stoppedNow(pool);
    var shut = pool.isShutdown();
    pool.shutdownNow();
    return shut;
  }

  // A shutdown hook: removing it says it was there.

  static Thread hooked(Runnable work) {
    var hook = new Thread(work);
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  static boolean hookedClaim(String a, String b) {
    return Runtime.getRuntime().removeShutdownHook(hooked(() -> {}));
  }

  // A Cleaner's clean(): its effect right away, not when the garbage collector gets to it.

  private static final Cleaner CLEANER = Cleaner.create();

  static void released(Cleaner.Cleanable cleanable) {
    cleanable.clean();
  }

  static boolean releasedClaim(String a, String b) {
    var done = new AtomicBoolean();
    var owner = new Object();
    var cleanable = CLEANER.register(owner, () -> done.set(true));
    released(cleanable);
    var cleaned = done.get();
    java.lang.ref.Reference.reachabilityFence(owner);
    return cleaned;
  }
}
