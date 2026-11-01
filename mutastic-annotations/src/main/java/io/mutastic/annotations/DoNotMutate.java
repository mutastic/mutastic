package io.mutastic.annotations;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// mutastic skips the mutants in this method, or in every method of this class, and reports them as
/// skipped with `value` as the reason: `@DoNotMutate("measured by the load tests")`.
@Documented
@Retention(CLASS)
@Target({TYPE, METHOD, CONSTRUCTOR})
public @interface DoNotMutate {
  /// Why: shown with each skipped mutant.
  String value() default "";
}
