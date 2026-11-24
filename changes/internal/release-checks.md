- **A release is checked before it's tagged, and its notes come from the changelog.**
  `scripts/release-check.sh X.Y.Z` checks what a release needs at once and lists what's wrong: no
  scoring change in a patch and no release without entries (`scripts/changelog.sh check --for
  X.Y.Z`), ids all kept, the docs' Rust pin on the Rust edition's `X.Y` with the shared pages'
  stamps up to date, and the files on Central. CI runs it on a release pull request.
  `scripts/changelog.sh notes X.Y.Z` prints the release's section for its GitHub release, in place
  of a hand-run `awk`, and `scripts/docs-pin-rust.sh <commit>` moves the Rust pin and stamps the
  shared pages it changes.
