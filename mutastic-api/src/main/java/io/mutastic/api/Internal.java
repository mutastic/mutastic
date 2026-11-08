package io.mutastic.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Public only so mutastic's own modules can share it: not part of the plugin API, and it may
/// change or go in any release. A plugin is written against [Mutator], [ClassMutator] and [Filter],
/// and the types their signatures name, less what this marks.
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Internal {}
