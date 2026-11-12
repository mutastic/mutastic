package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Stands in for AndroidX's own, with its name, retention, targets and defaults.
@Retention(RetentionPolicy.CLASS)
@Target({
  ElementType.METHOD,
  ElementType.PARAMETER,
  ElementType.FIELD,
  ElementType.LOCAL_VARIABLE,
  ElementType.ANNOTATION_TYPE
})
public @interface IntRange {
  long from() default Long.MIN_VALUE;

  long to() default Long.MAX_VALUE;
}
