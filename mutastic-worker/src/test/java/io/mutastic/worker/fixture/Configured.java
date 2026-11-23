package io.mutastic.worker.fixture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/// Found by a scan through the annotation on it, as Spring's `@Configuration` is a `@Component`.
@Retention(RetentionPolicy.RUNTIME)
@Marker
public @interface Configured {}
