package io.mutastic.api;

import java.util.List;

/// A ghost: an insertion mutant, for code that most sites like this one have and this one lacks. It
/// adds the call the majority makes, here a property change that a write doesn't announce where its
/// peers do, and the tests that reach the write run against it. It's experimental, and never in the
/// score: a ghost no test notices is a question about what the code should do, not a gap the score
/// should count.
///
/// A ghost *appears* when a test notices the inserted call, which says that test expects the
/// silence, and *passes unseen* when none does.
///
/// @param result the inserted call's run: caught is appeared, missed is passed unseen
/// @param field the field the write is to
/// @param property the property name the inserted call fires
/// @param rule why mutastic thinks the call belongs there
/// @param peers the writes that do announce themselves, that the call was copied from
/// @param writes how many writes the rule counted, this one among them: of the field, for
///   [Rule#SAME_FIELD]; of the class's properties, one per field, for [Rule#SIBLING_FIELDS]
@Internal
public record Ghost(
    MutantResult result, String field, String property, Rule rule, List<Peer> peers, int writes) {

  public Ghost {
    peers = List.copyOf(peers);
  }

  /// Why a missing call looks missing.
  public enum Rule {
    /// Other writes to the same field fire a property change, and this one doesn't.
    SAME_FIELD,
    /// The class's other properties fire a property change on every write, and this setter's
    /// doesn't.
    SIBLING_FIELDS
  }

  /// A write that does fire: where it is, and the property it names.
  ///
  /// @param method the method's name, `deposit`
  public record Peer(String method, int line, String property) {}

  /// Whether a test noticed the inserted call.
  public boolean appeared() {
    return result.status().detected();
  }

  /// Whether every test that reaches the write passed with the call in.
  public boolean passedUnseen() {
    return result.status() == Status.MISSED;
  }
}
