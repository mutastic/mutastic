- **A release ships what the merge queue tested, without testing it again.** The release job ran
  the whole build on one runner, from 25 minutes for 1.0.0 to 52 for 1.3.1, against a 60-minute
  limit, though the queue had already run the same tests on the same tree in parallel jobs. It now
  releases only a commit on `main` that CI passed, in the queue or on the push to `main`, and says
  which run is missing or red before anyone is asked to approve; then it builds what ships and
  runs the checks only a release can: the plugin API against the last release, the ids, the files
  on Central and the plugin for the portal, in about five minutes.
