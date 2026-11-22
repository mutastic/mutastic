package output;

import java.io.PrintStream;
import java.io.PrintWriter;

/// `mutastic:output`'s calls, by method name: `only…` writes only output, to the console or a
/// logger, and its calls are arid; `kept…` writes to a stream or a writer the code is given, which
/// a test can read back, and its calls stay counted. `guard…` checks a log level around a body
/// that only writes output, so the check is arid too; `keptGuard…` does more, or is shaped
/// otherwise, and its check stays counted. Each returns what it works out, which no output
/// changes.
class Printed {
  private final PrintStream out;
  private org.slf4j.Logger slf4j;
  private ch.qos.logback.classic.Logger logback;
  private org.apache.logging.log4j.Logger log4j;
  private org.apache.log4j.Logger log4j1;
  private org.jboss.logging.Logger jboss;
  private io.github.oshai.kotlinlogging.KLogger klogger;
  private com.google.common.flogger.FluentLogger flogger;
  private int saved;

  Printed(PrintStream out) {
    this.out = out;
  }

  int onlySystemOut(int a) {
    System.out.println("a is " + a);
    return a + 1;
  }

  int onlySystemErr(int a) {
    System.err.print(a);
    return a + 2;
  }

  int onlyPrintf(int a, int b) {
    System.out.printf("%d and %d%n", a, b);
    return a + b;
  }

  int onlyChosenText(int a) {
    System.out.println(a > 0 ? "positive" : "not");
    return a * 2;
  }

  int onlyAConsoleLocal(int a) {
    PrintStream console = System.out;
    console.println(a);
    console.flush();
    return a - 1;
  }

  int onlyFluentSlf4j(int a) {
    slf4j.atInfo().addArgument(a).log("a is {}");
    return a + 3;
  }

  int onlyFluentSlf4jMessage(int a) {
    slf4j.atDebug().setMessage("a").addArgument(a).log();
    return a + 4;
  }

  int onlyLogback(int a) {
    logback.warn("a is " + a);
    return a + 5;
  }

  int onlyLog4jBuilder(int a, RuntimeException e) {
    log4j.atInfo().withThrowable(e).log("a is {}", a);
    return a + 6;
  }

  int onlyLog4j1(int a) {
    log4j1.info("a is " + a);
    return a + 7;
  }

  int onlyJboss(int a) {
    jboss.infof("a is %d", a);
    return a + 8;
  }

  int onlyKotlinLogging(int a) {
    klogger.info("a is " + a);
    return a + 9;
  }

  int onlyFlogger(int a, RuntimeException e) {
    flogger.atInfo().withCause(e).log("a is %d", a);
    return a + 10;
  }

  int onlyTinylog(int a) {
    org.tinylog.Logger.info("a is {}", a);
    org.tinylog.Logger.tag("audit").warn("seen");
    return a + 11;
  }

  int keptAField(int a) {
    out.println("a is " + a);
    return a + 1;
  }

  int keptAParameter(PrintStream to, int a) {
    to.println("a is " + a);
    return a + 1;
  }

  static int keptAStaticParameter(PrintStream to, int a) {
    to.print(a);
    return a + 1;
  }

  int keptALocalOfAParameter(PrintStream to, int a) {
    PrintStream chosen = to;
    chosen.println(a);
    return a + 1;
  }

  int keptALocalOfEither(PrintStream to, int a) {
    PrintStream chosen = System.out;
    if (a > 0) {
      chosen = to;
    }
    chosen.println(a);
    return a + 1;
  }

  int keptAWriter(PrintWriter to, int a) {
    to.println("a is " + a);
    return a + 1;
  }

  int guardSlf4j(int a) {
    if (slf4j.isDebugEnabled()) {
      slf4j.debug("a is {}", a);
    }
    return a + 1;
  }

  int guardTwoStatements(int a, int b) {
    if (slf4j.isDebugEnabled()) {
      slf4j.debug("a is {}", a);
      slf4j.debug("{} and {}", a > b ? "more" : "less", "a" + b);
    }
    return a + b;
  }

  int guardFluent(int a) {
    if (slf4j.isDebugEnabled()) {
      slf4j.atDebug().addArgument(a).log("a is {}");
    }
    return a + 2;
  }

  int guardLog4j(int a) {
    if (log4j.isDebugEnabled()) {
      log4j.debug("a is {}", a);
    }
    return a + 3;
  }

  int guardJul(java.util.logging.Logger jul, int a) {
    if (jul.isLoggable(java.util.logging.Level.FINE)) {
      jul.fine("a is " + a);
    }
    return a + 4;
  }

  int guardJboss(int a) {
    if (jboss.isTraceEnabled()) {
      jboss.tracef("a is %d", a);
    }
    return a + 5;
  }

  int keptGuardAStore(int a) {
    if (slf4j.isDebugEnabled()) {
      saved++;
      slf4j.debug("a is {}", a);
    }
    return a + 1;
  }

  int keptGuardOwnWork(int a) {
    if (slf4j.isDebugEnabled()) {
      slf4j.debug("a is {}", a);
      save(a);
    }
    return a + 1;
  }

  int keptGuardALocal(int a) {
    int shown = a;
    if (slf4j.isDebugEnabled()) {
      shown = a * 2;
      slf4j.debug("a is {}", shown);
    }
    return shown;
  }

  int keptGuardAnElse(int a) {
    if (slf4j.isDebugEnabled()) {
      slf4j.debug("a is {}", a);
    } else {
      save(a);
    }
    return a + 1;
  }

  int keptGuardAndMore(int a) {
    if (slf4j.isDebugEnabled() && a > 0) {
      slf4j.debug("a is {}", a);
    }
    return a + 1;
  }

  int keptGuardAParameterStream(PrintStream to, int a) {
    if (slf4j.isDebugEnabled()) {
      to.println(a);
    }
    return a + 1;
  }

  int keptGuardAReturn(int a) {
    if (slf4j.isDebugEnabled()) {
      slf4j.debug("a is {}", a);
      return a;
    }
    return a + 1;
  }

  private void save(int a) {
    saved += a;
  }
}
