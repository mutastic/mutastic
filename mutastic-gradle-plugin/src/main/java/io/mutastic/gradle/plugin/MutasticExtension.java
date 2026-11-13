package io.mutastic.gradle.plugin;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.jvm.toolchain.JavaLauncher;

/// `mutastic { }`: how a run happens, for every mutastic task in the project, each of which can
/// also be set on its own. A `-Pmutastic.<name>` on the command line wins over both, for one run; a
/// list given there replaces the build file's. What to mutate (skip and mutate rules, plugins and
/// filters) is in `mutastic.yaml`, which the `mutastic` command and both build plugins share.
public abstract class MutasticExtension {
  /// The score a run must reach, 80 unless set.
  public abstract Property<Integer> getThreshold();

  /// Other modules whose tests run over the mutants this module's tests miss: `:app`, or
  /// `dependents` for every module that depends on this one.
  public abstract ListProperty<String> getTestsFrom();

  /// Which `Test` task's tests run, and its source set's: `test` unless set, `integrationTest` say.
  public abstract Property<String> getSuite();

  /// How many workers run side by side; unset, it depends on the machine.
  public abstract Property<Integer> getWorkers();

  /// The report files a full run writes: `json`, `html` and `xml`, each with `:path` if it isn't
  /// the default. `json` and `html` unless set; none when empty.
  public abstract ListProperty<String> getReports();

  /// `full`, `brief`, `concise`, `grouped`, `agent` or `jsonl`; unset, `concise`, as Gradle's
  /// output isn't a terminal.
  public abstract Property<String> getFormat();

  /// Pass a run when every mutant no test catches is in `.mutastic/baseline.yaml`, and fail it on
  /// one that isn't, whatever the score: the threshold isn't applied. Off unless set.
  public abstract Property<Boolean> getFailOnNew();

  /// Mutate only the lines changed since this ref; empty for the default branch.
  public abstract Property<String> getSince();

  /// Run a catching test again on its own, to confirm the catch. Off unless set.
  public abstract Property<Boolean> getConfirmCatches();

  /// A card for every missed mutant, not one for the mutants of each line. Off unless set.
  public abstract Property<Boolean> getEachMutant();

  /// Suggest a test for each missed mutant. On unless set.
  public abstract Property<Boolean> getSuggestions();

  /// The version of the `mutastic` command the tasks run: the plugin's own unless set.
  public abstract Property<String> getToolVersion();

  /// The JDK mutastic runs on, 25 or later for the ClassFile API: a JDK 25 toolchain unless set.
  /// The tests still run on the JDK the `test` task uses.
  public abstract Property<JavaLauncher> getJavaLauncher();
}
