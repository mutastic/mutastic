package io.mutastic.gradle.plugin;

import javax.inject.Inject;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;

/// Gradle's own progress line, through its internal `ProgressLoggerFactory`: the one place the
/// plugin reaches inside Gradle. It's made only inside [Footer]'s guard, so a Gradle without it
/// fails there, and the run shows the heartbeat's lines instead.
public abstract class ProgressLoggers {
  @Inject
  protected abstract ProgressLoggerFactory getFactory();

  /// An operation under the task's, whose status is the line's text after the task's name.
  Footer.Line start(String description) {
    var logger = getFactory().newOperation(MutasticTask.class);
    logger.start(description, "");
    return new Footer.Line() {
      @Override
      public void say(String status) {
        logger.progress(status);
      }

      @Override
      public void close() {
        logger.completed();
      }
    };
  }
}
