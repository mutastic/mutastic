- **A release is a tag and one approval, and goes out in order.** 1.3.0 took about nine hours and a
  dozen steps by hand: an approval, Publish pressed in the Central Portal, a second workflow and
  approval for the Gradle Plugin Portal, which once ran before Central had the release, and docs
  deployed on the tag push, before Central had it. Now pushing `vX.Y.Z` builds once, uploads to
  Central and waits for its validation, builds the docs and makes a draft GitHub release with the
  attested assets, then shows the approver a summary made by a script: the deployment, its files
  against Central's monthly limits, and the notes. One approval publishes Central's deployment
  through the Publisher API (`scripts/central.sh`, curl over the API, so it doesn't depend on
  Gradle), waits for repo1 to serve it, then publishes the Gradle plugin, the GitHub release and
  the docs, and opens the pull request that holds the next release to this one. Rejected, or
  failed before Central published it, the deployment is dropped. A release candidate's tag,
  `vX.Y.0-rc1`, starts neither the release nor the docs' deploy, and `just release` pushes the
  tag. The files on Central are signed in the stage, with a key of its own, `0E561074`, where
  1.4.0 and before have `6C90C775` (RELEASING.md, *Signing keys*).
