package io.mutastic.api;

import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.util.List;
import java.util.Optional;

/// An operator a plugin adds, or a rules file describes: which instructions it changes, how, and
/// what a missed mutant's card says. Plugins provide theirs as a [java.util.ServiceLoader] service,
/// in a jar `mutastic.yaml` lists under `plugins:`.
///
/// Each mutant's id is made from its method, its site and its change's label, with the operator's
/// id, so a change's label must never change once released. A call operator's sites are calls,
/// counted as the built-in call operators count them; any other operator's are every instruction in
/// the method, counted in order.
public non-sealed interface Mutator extends Operator {
  /// The changes this operator makes at `site`, each by its label; none when it doesn't apply.
  List<Change> changes(Site site);

  /// Writes `instruction` in its place, changed as `label` says. The stack afterwards must hold
  /// what the original left; a change that doesn't verify is skipped as unviable, never run.
  void write(CodeBuilder builder, Instruction instruction, String label);

  /// What a missed mutant's card says.
  Words words(Mutant mutant);

  @Override
  default Tier tier() {
    return Tier.DEFAULTS;
  }

  /// One instruction, with the code around it.
  ///
  /// @param index where `instruction` is in `code`
  record Site(
      ClassModel owner,
      MethodModel method,
      List<CodeElement> code,
      int index,
      Instruction instruction) {}

  /// One change at a site.
  ///
  /// @param label what the instruction becomes, short: `allMatch`, `stage removed`
  /// @param skipped why it shouldn't run, if it shouldn't, kind first: `equivalent: …`
  record Change(String label, Optional<String> skipped) {
    public static Change of(String label) {
      return new Change(label, Optional.empty());
    }
  }

  /// A missed mutant's card, in words.
  ///
  /// @param changed what the source line now does: `stage removed`, `anyMatch changed to allMatch`
  /// @param headline the gap it shows: ``no test needs `.filter(…)` in total()``
  /// @param help the test that would catch it
  /// @param written for a change to a call, the call as the source would be written with the change
  ///   made, so the card can show the line as the mutant has it: a template on the call's parts as
  ///   the source writes them. `{receiver}` is what it's called on, `{args}` its arguments and
  ///   `{arg1}`, `{arg2}` each one: `{receiver}.allMatch({args})`, `{receiver}` for a call left
  ///   out, `{arg1} + {arg2}`. Empty when the change isn't one the call's source can show, and the
  ///   card says it in words.
  record Words(String changed, String headline, String help, Optional<String> written) {
    /// Words for a change the source can't show.
    public Words(String changed, String headline, String help) {
      this(changed, headline, help, Optional.empty());
    }
  }
}
