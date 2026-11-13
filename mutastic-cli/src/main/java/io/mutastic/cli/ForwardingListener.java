package io.mutastic.cli;

import io.mutastic.api.CoveragePass;
import io.mutastic.api.Listener;
import io.mutastic.api.MutantResult;
import java.util.List;

/// Tells another listener all it hears. Something that hears more overrides a method and calls this
/// one's, so a new kind of event reaches progress without each such listener being changed.
class ForwardingListener implements Listener {
  private final Listener to;

  ForwardingListener(Listener to) {
    this.to = to;
  }

  @Override
  public void mutantsFound(int mutants) {
    to.mutantsFound(mutants);
  }

  @Override
  public void coverageProgress(int done, int planned) {
    to.coverageProgress(done, planned);
  }

  @Override
  public void coverageFinished(CoveragePass pass) {
    to.coverageFinished(pass);
  }

  @Override
  public void mutantFinished(MutantResult result) {
    to.mutantFinished(result);
  }

  @Override
  public void fileFinished(String topLevelClass, List<MutantResult> results) {
    to.fileFinished(topLevelClass, results);
  }

  @Override
  public void mutantRevised(MutantResult result) {
    to.mutantRevised(result);
  }

  @Override
  public void checking(String step, int done, int total) {
    to.checking(step, done, total);
  }
}
