package io.mutastic.api;

import java.util.Optional;

/// A test the run was told to leave out (`--exclude-tests`, `exclude-tests:`) that names none of
/// its tests, so it left nothing out: a run says so rather than run as if it had (decision 28).
///
/// @param test the name as it was given
/// @param closest the one test a run names that it's close to, by [Nearest]'s rule: offered, never
///   acted on
@Internal
public record UnmatchedExclusion(String test, Optional<String> closest) {}
