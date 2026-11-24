/// The test frameworks a worker runs a project's tests with, one part each behind
/// [io.mutastic.engine.tests.TestFrameworks]: the JUnit Platform itself, TestNG, JUnit 4 through
/// the vintage engine, and Quarkus, which loads the app itself. What each needs is decided here
/// alone, so the rest of the engine names none of them.
@NullMarked
package io.mutastic.engine.tests;

import org.jspecify.annotations.NullMarked;
