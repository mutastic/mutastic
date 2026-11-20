package io.mutastic.annotations;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// mutastic mutates this method, or every method of this class, even where it would skip it as
/// arid: a `toString()` that customers see, a getter that is a contract, a log call an alert
/// watches. Every arid skip in it comes back, a whole method's or one call's; generated and
/// equivalent code stays skipped.
@Documented
@Retention(CLASS)
@Target({TYPE, METHOD, CONSTRUCTOR})
public @interface DoMutate {
  /// Why it matters: for the reader.
  String value() default "";

  /// Only these arid rules' skips come back, by id: `@DoMutate(value = "callers wait for it", arid
  /// = "mutastic:waits")`. Empty, every arid skip. Each rule is on [Arid
  /// rules](https://docs.mutastic.io/reference/arid/).
  String[] arid() default {};
}
