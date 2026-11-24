- **Holding the next release to the last one is a script.** After each release, a dozen lines of
  RELEASING.md were copied by hand into their own pull request, to set `lastStableRelease` and
  keep the release's schemas, rules files, `mutastic.yaml` files and `commands.json` for the
  compatibility checks. `scripts/hold-release.sh X.Y.Z` does it from the tag, refuses until the
  release is on Maven Central, changes nothing when run again, and lists the schema's keys the
  every-key file doesn't use. CI checks it writes 1.3.0's hold as it was made by hand.
