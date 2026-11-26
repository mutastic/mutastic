/// The output kit: what a tool in the family prints with, whatever it tests. Colour roles, glyphs
/// and labels; text from the project escaped and fenced for an agent; JSON and an event stream's
/// envelope; a live progress block, its bar and heartbeat; help's layout; an error's remedies
/// graded; a command line corrected.
///
/// It knows nothing of mutastic: no mutants, cards or scores, and nothing else of mutastic's, as
/// `ArchitectureTest` holds it. mutastic's report and command use it; a sibling tool takes it
/// whole. Public only so they can: not part of the plugin API, and it may change in any release.
@NullMarked
package io.mutastic.output;

import org.jspecify.annotations.NullMarked;
