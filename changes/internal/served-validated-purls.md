- **The release waits for repo1 to serve the components Central validated.** 1.4.1's release
  (run 45879952796) failed after Central published it: `scripts/central.sh served` read the
  components from the published deployment's status, and Central lists them only while it's
  VALIDATED. `served` now reads them from the status `stage` saves then, `central.json`, and still
  checks the deployment is PUBLISHED; the tests' stand-in for Central lists none once published,
  as Central does. `drop` no longer runs once Central has published the deployment, which it
  can't drop, and RELEASING.md's *When a step fails* lists 1.4.1's steps by hand.
