# Releasing mutastic

A release reaches four places, and once it's out on Central it can never be changed or removed:
every library module on Maven Central as `io.mutastic:<module>`, signed; the Gradle plugin on the
Gradle Plugin Portal; the CLI's distribution and a CycloneDX SBOM on the GitHub release, each
attested ([Checking a download](#checking-a-download)); and its docs on
[docs.mutastic.io](https://docs.mutastic.io). So it's built and staged first, where it can still
be thrown away, and published with one approval, in that order.

Pushing the tag `vX.Y.Z` starts `.github/workflows/release.yml`. It checks CI passed on the tag's
commit, builds what ships once, uploads it to Central as a deployment that isn't published, waits
for Central to validate it, builds the docs, and makes a draft GitHub release with the assets and
their attestations. Its run's summary, made by `.github/release-summary.sh`, says what the
approval will publish. Approving the `release` environment then publishes Central's deployment,
waits for repo1 to serve it, publishes the Gradle plugin, publishes the GitHub release, deploys
the docs and opens the pull request that holds the next release to this one. Rejecting it drops
the deployment. The calls to Central are `scripts/central.sh`, curl over the Central Portal's
[Publisher API](https://central.sonatype.org/publish/publish-portal-api/), so they don't depend on
Gradle.

## Once

1. **The namespace.** On [central.sonatype.com](https://central.sonatype.com), go to *Namespaces*,
   then *Add Namespace*, and enter `io.mutastic`. Add the verification key it shows as a DNS TXT
   record on `mutastic.io` (its DNS is at Cloudflare), then press *Verify*.
2. **A token.** In the Central Portal, open *View Account*, then *Generate User Token*. Keep the
   username and password it shows for step 4.
3. **A signing key,** used only for mutastic's releases:
   ```sh
   brew install gnupg
   # Give it a passphrase when asked: it protects the private key wherever a copy of it sits.
   gpg --quick-gen-key "mutastic releases <your email>" rsa4096 sign 3y
   gpg --list-secret-keys   # the 40 hex digits under `sec` are its fingerprint
   FPR=<fingerprint>
   # Gradle takes only the short id, the fingerprint's last 8 digits: a full one is refused
   # ("The key ID must be in a valid form").
   KEY=${FPR: -8}
   # Central checks signatures against public keyservers.
   gpg --keyserver keyserver.ubuntu.com --send-keys $FPR
   gpg --keyserver keys.openpgp.org --send-keys $FPR   # emails a link: until it's followed, the key has no name
   ```
   Back up the private key and its revocation certificate (`~/.gnupg/openpgp-revocs.d/`), for
   example in a password manager. If you made the key without a passphrase, add one with
   `gpg --passwd $KEY`; the public key and fingerprint don't change.
4. **Two environments and their secrets.** `stage` builds, signs and uploads a release before
   the approval, so it has no reviewer; `release` publishes it, so it has you as its required
   reviewer, and no tag is published without you. Each lets only `v*` tags deploy to it:
   ```sh
   for env in stage release; do
     gh api -X PUT repos/mutastic/mutastic/environments/$env \
       -F 'deployment_branch_policy[protected_branches]=false' -F 'deployment_branch_policy[custom_branch_policies]=true'
     gh api -X POST repos/mutastic/mutastic/environments/$env/deployment-branch-policies -f name='v*' -f type=tag
   done
   ```
   Then add yourself under *Settings → Environments → release → Required reviewers*. The signing
   key is needed only to upload, so only `stage` has it; both have the Central token, which
   uploads and validates in one and publishes in the other:
   ```sh
   # The private key goes straight from gpg to GitHub: it's never printed.
   gpg --armor --export-secret-keys $KEY | gh secret set SIGNING_KEY --env stage --repo mutastic/mutastic
   gh secret set SIGNING_KEY_ID --env stage --repo mutastic/mutastic --body $KEY   # the short id
   # These prompt, so the values stay out of shell history.
   gh secret set SIGNING_KEY_PASSWORD --env stage --repo mutastic/mutastic    # the key's passphrase
   for env in stage release; do
     gh secret set MAVEN_CENTRAL_USERNAME --env $env --repo mutastic/mutastic  # the token's username, from step 2
     gh secret set MAVEN_CENTRAL_PASSWORD --env $env --repo mutastic/mutastic  # the token's password, from step 2
   done
   ```
   The token is not your Central Portal login, and the portal shows it once. Tokens belong to the
   account, not the namespace, and generating a new one may retire the old. Central's tokens can't
   be limited to uploading, so `stage`'s could publish too: what keeps a tag from reaching it
   unreviewed is that only a commit `main` has and CI passed is staged, and the tag ruleset in step
   8.
5. **The Gradle Plugin Portal.** Sign in at [plugins.gradle.org](https://plugins.gradle.org), and
   on your profile's *API Keys* tab generate a key and secret. Both environments have it: the
   portal checks a plugin (`publishPlugins --validate-only`) with the same key it publishes with.
   ```sh
   for env in stage release; do
     gh secret set GRADLE_PUBLISH_KEY --env $env --repo mutastic/mutastic
     gh secret set GRADLE_PUBLISH_SECRET --env $env --repo mutastic/mutastic
   done
   ```
   The portal reviews a plugin's first version by hand, usually in a few days, and asks for proof
   of `mutastic.io` with a DNS TXT record. Its DNS is at Cloudflare: *mutastic.io → DNS →
   Records*, a TXT record on `@`, beside the Central one. Keep both.
6. **The docs site.** A Cloudflare Pages project and two repository secrets, so the release and
   docs workflows can deploy: [docs/README.md](docs/README.md) has the steps. They're the
   repository's, not an environment's, as docs.yml's previews use them too, and the release
   workflow deploys the docs after its one approval.
7. **A signed dry run,** to check the key works before a real release:
   ```sh
   read -s "PASS?key password: "   # zsh; in bash, read -sp "key password: " PASS
   # GnuPG asks for the passphrase before exporting a secret key: give it $PASS rather than a prompt.
   ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --pinentry-mode loopback --passphrase "$PASS" --armor --export-secret-keys $KEY)" \
   ORG_GRADLE_PROJECT_signingInMemoryKeyId=$KEY \
   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$PASS" \
     ./gradlew publishToMavenLocal -PreleaseVersion=0.0.0-dryrun
   ls ~/.m2/repository/io/mutastic/mutastic-api/0.0.0-dryrun/   # each file has an .asc beside it
   rm -rf ~/.m2/repository/io/mutastic
   ```
8. **Tags.** A `v*` tag starts a release, so only you should be able to push one: a ruleset on
   tags `v*` that restricts creating, updating and deleting them, with you able to bypass it
   (*Settings → Rules → Rulesets → New tag ruleset*).
9. **Releases that can't change.** *Settings → General → Releases → Enable release immutability*:
   once a release is published, its assets and tag are locked, and GitHub attests them. The
   workflow attaches every asset to the draft, before it's published, as that requires.
10. **The hold pull request.** *Settings → Actions → General → Allow GitHub Actions to create and
    approve pull requests*, so the release workflow can open it.

## Each release

1. **The release commit.** With `main` green, gather the notes and check the release
   ([Before the release commit](#before-the-release-commit) says what's checked):
   ```sh
   scripts/changelog.sh preview X.Y.Z   # the section, as it will read: read it
   scripts/changelog.sh release X.Y.Z   # writes it into CHANGELOG.md, deletes the entries
   scripts/release-check.sh X.Y.Z       # says it's ready, or lists what isn't
   ```
   Commit it as `chore(release): version X.Y.Z` in a pull request of its own, and merge it. Run
   the [candidate](#before-tagging) on it first.
2. **The tag,** on that commit once it's on `main` and CI passed on it:
   ```sh
   git fetch origin && git tag vX.Y.Z <the release commit> && git push origin vX.Y.Z
   ```
   The release workflow starts. In a few minutes it has uploaded the release to Central, had it
   validated, and made a draft release with its assets; it stops before publishing anything if CI
   didn't pass on the commit, the changelog has no `X.Y.Z` section, or a check fails.
3. **Read the summary** at the top of the run (`gh run watch`, or *Actions → Release*): the
   version and commit, the Central deployment, its components and files against Central's monthly
   limits, the notes, and links to the draft release and the Central Portal. Look at the draft's
   assets if you like: nobody else can see it yet.
4. **Approve once,** *Review deployments → release → Approve*, or reject to drop the deployment.
   From the approval it publishes, in order: Central (up to about half an hour), the Gradle
   plugin, the GitHub release, then the docs; then it opens `build: hold the next release to
   X.Y.Z`. That pull request starts no checks of its own, as the workflow's token opened it: read
   it as [Holding the next release](#holding-the-next-release) says, close and reopen it so CI
   runs, and merge it with `just merge`.
5. **Check release-verify.** Publishing the GitHub release starts `release-verify.yml`, which
   checks the release as a person gets it ([After a release](#after-a-release)). For `X.Y.0`, tag
   the Rust edition's `vX.Y.0` once the GitHub release is published, not before: its release
   workflow builds nothing until this release has its CLI.

## When a step fails

Each job says what failed in its log. Whether to fix forward depends on whether Central has
published the release:

- **Before the approval** (`tested`, `stage`, `docs`, `draft`), **or rejected:** nothing was
  published. The deployment is dropped (`stage` drops its own; `drop` the rest), and the draft and
  the tag were seen by no one. Fix the cause, delete them, and tag the fixed commit again, the same
  version:
  ```sh
  gh release delete vX.Y.Z --cleanup-tag --yes   # the draft, if there is one, and the tag
  git push origin :refs/tags/vX.Y.Z              # the tag, when there's no draft
  ```
  A failed `tested` needs no fix but a CI run that passes: run it again once one has.
- **Central publishing, or repo1 not serving it yet:** `scripts/central.sh` says which. Run the
  `publish` job again: a deployment already published isn't published twice, and it waits for
  repo1 again.
- **After Central published it** (the Plugin Portal, the GitHub release, the docs or the hold):
  the version is out and can't be changed, so don't delete anything. Run the failed job again
  (*Re-run failed jobs*), or do its part by hand:
  ```sh
  gh workflow run portal.yml --ref vX.Y.Z                    # the Gradle plugin; approve it
  gh release edit vX.Y.Z --draft=false                       # the GitHub release
  gh workflow run docs.yml --ref main -f release=true        # docs.mutastic.io, every release line
  scripts/hold-release.sh X.Y.Z                              # the hold, on a branch from main
  ```
  If what was published is wrong, card it, fix the cause and release `X.Y.Z+1`. `drop` fails here,
  saying Central has published the deployment: that's expected.

A deployment left behind (a run cancelled before `drop`, or one the upload named no id for) is
dropped with `scripts/central.sh drop <id>`, with `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD` set, or with *Drop* under *Deployments* in the Central Portal.

## Before the release commit

The build already fails on a moved mutant id, a break in the plugin API and a break in the events
format ([Compatibility](docs/src/content/docs/reference/compatibility.mdx)).
`scripts/release-check.sh X.Y.Z` checks what a release needs beyond that, all at once, and lists
what's wrong: the changelog's rules, the ids, the Rust docs pin and the shared pages' stamps, and
the files on Central. It writes nothing tracked and publishes nothing, so run it as often as you
like; CI runs it on the release pull request too, the part that needs no build in Commits and the
ids and Central in CI's merge queue run, and the release workflow runs the ids and Central again on
the tag. What it can't judge is left to you:

- **Scores.** Read the *Scoring changes* and *Score corrections* that
  `scripts/changelog.sh preview` prints, the entries waiting in `changes/`. A patch release
  (`X.Y.Z+1`) may have score corrections, which only drop mutants that could never run, such as
  code the build never compiles counted by mistake: read each, and hold the patch back if one
  could lower a score, add a mutant or fail a run that passed. It has no scoring changes:
  anything else that moves a score (an operator into or out of the default set, a filter that
  skips more or less, a change to timeouts, mutants newly found) waits for a minor release. A
  minor release's notes say which scores move, and how. `release-check` fails a patch with a
  scoring change, and a release with no entries at all.
- **Ids.** When `release-check` says `./gradlew :mutastic-cli:exportIds` changed
  `src/test/ids/ids.tsv`, it's an operator new since the last release: read the diff and commit
  it, so its ids are held from this release on.
- **The Rust edition.** From 1.0.0 the editions release each minor version together, Java
  published first, then Rust tagged: for `X.Y.0`, the Rust edition's release commit is on its
  `main` (`RELEASING.md` in `mutastic/mutastic-rs`), and its tag waits for this release, as the
  native `mutastic X.Y.z` runs the newest Java `X.Y.*`. A patch (`X.Y.Z+1`) may ship alone:
  everyone on the native `X.Y.*` gets it within a day, once its GitHub release is published.
  Until then, the native `mutastic` keeps running the patch it has.
- **The Rust docs pin.** The docs a release deploys show the Rust edition's pages from
  `docs/rust-edition.json` as it is at that tag, for as long as that release line is published,
  so move the pin before tagging, not after. For `X.Y.0`: once the Rust edition's release commit
  (`chore(release): version X.Y.0`) is on its `main`, move the pin to it:
  ```sh
  scripts/docs-pin-rust.sh <commit>   # the release commit's hash, or a mutastic-rs branch or tag
  ```
  It sets `commit` to that commit's full hash and `version` to what its `Cargo.toml` says (its
  tag comes after this release is published, so the pin names the commit, and the docs build
  checks the two agree). Then it stamps again each shared page whose mutastic-rs source changed
  since its stamp, the pages `scripts/check-editions.mjs` names, which would otherwise stop the
  release's docs build. A stamp says the page is up to date, so bring each page it lists up to date
  with the Rust edition, from the diff it prints, before you commit. Commit the pin, the stamps
  and the pages with `main` green; `release-check` fails while the pin isn't an `X.Y.*` or a
  stamp is stale. The Rust edition's
  `vX.Y.0` is tagged only once this release's GitHub release is published: its release workflow
  builds nothing until then, as the native `mutastic X.Y.0` would find nothing to run.
- **Files on Central.** Central counts every file against a monthly limit
  ([Usage](https://central.sonatype.com/publishing/usage)). `checkCentralFiles` keeps a release
  small enough for four a month, and `release-check` runs it. The summary you approve from says
  this release's files and how many releases this month already had; look at the usage before a
  second release in a month.

The release's notes are its section of `CHANGELOG.md`. Each entry since the last release is a
file in `changes/` ([changes/README.md](changes/README.md)), so pull requests don't conflict over
`CHANGELOG.md`; `scripts/changelog.sh release X.Y.Z` gathers them as `## X.Y.Z (D Mon YYYY)`,
dated today, and deletes the files. Edit the section if it needs it, a paragraph above a section's
entries or one at the top: only a commit with the `release` scope may edit `CHANGELOG.md`. The
release workflow gives the GitHub release the same section (`scripts/changelog.sh notes X.Y.Z`).

## Holding the next release

From 1.0.0 on, each release holds the next to it: its plugin API, events format, rules files,
`mutastic.yaml` and command line. Once Central has the release, the release workflow runs, on a
branch from `main`, `scripts/hold-release.sh X.Y.Z` (which refuses until X.Y.Z's mutastic-api jar
is on Central), and opens its pull request, `build: hold the next release to X.Y.Z`. To run it by
hand, on a branch from `main`:
```sh
scripts/hold-release.sh X.Y.Z
```
It sets `lastStableRelease` in `gradle.properties` to X.Y.Z, so `checkApiCompatibility`
compares the plugin API with its jar, and keeps its schemas, rules files, `mutastic.yaml` files
and `commands.json`, as the tag `vX.Y.Z` has them, in place of the last release's, so
`SchemaCompatibilityTest`, `RulesFileFormatTest`, `ConfigurationFormatTest` and
`CommandLineCompatibilityTest` compare with them
([Schemas and stability](docs/src/content/docs/reference/schemas.mdx) says what counts as
breaking). Then it runs `exportIds`, which writes each rules file's and `mutastic.yaml`'s
`.read` beside it. Running it again changes nothing.

The every-key `mutastic.yaml` is the last release's, with any key this one added: the script
copies the last release's and lists the schema's keys it doesn't use, marking those new since
the last release, and the pull request shows that list. Add the ones this release added, as #183
did for 1.3.0, and run the script again for their `.read`, on the pull request's branch.
`scripts/test-hold-release.sh`, in CI's build job, checks the script still writes #183's tree.
Until a release does, a field added since the last one can still change or go.

## Before tagging

Run the candidate: the CLI built from the release commit, over a share of every sample's mutants
(`scripts/candidate-run.sh`, a few minutes). It fails when a run crashes (`internal`), can't run
(`run-failed`), or finds the tests fail without any mutant, outside the samples built to. Run it on
the release's branch, or locally:
```sh
gh workflow run candidate-run.yml --ref <branch>
scripts/candidate-run.sh             # --of=N for a smaller share, or name samples to run
```
Don't tag a candidate that fails: its run's summary and artifact say which sample, and how.

## After a release

`release-verify.yml` checks the release as a person gets it, on Linux, macOS and Windows, with
nothing cached: the CLI's zip against its SHA256SUMS, GitHub's record of each asset and the release
workflow's attestation of it, the Maven plugin from Central, and the Gradle plugin through the
Plugin Portal, each on `samples/pricing`, where it must print the score line `PricingSampleTest`
expects. The release workflow starts it once it has published the GitHub release, and it waits up
to an hour for Central's mirrors and the Plugin Portal; a release published by hand starts it too,
waiting up to four hours. Look at it in *Actions*; to check a release again, or once a wait ran
out:
```sh
gh workflow run release-verify.yml -f version=X.Y.Z
```
A failure's summary says what failed and where. Central can't change or remove a version, so fix
forward: card it, fix the cause, release `X.Y.Z+1`.

## Checking a download

`SHA256SUMS` is made beside the archives, so a file swapped along with it still matches. Each
asset of every release after 1.3.1 (the CLI's zip and tar, `SHA256SUMS` and the SBOM,
`mutastic-X.Y.Z.cdx.json`) is also attested: GitHub's signed record that the release workflow built
it, at the release's tag. Check one with the GitHub CLI:
```sh
gh attestation verify mutastic-X.Y.Z.zip --repo mutastic/mutastic
```
`--signer-workflow mutastic/mutastic/.github/workflows/release.yml --source-ref refs/tags/vX.Y.Z`
holds it to that workflow and tag, as `release-verify.yml` does, and `--predicate-type
https://cyclonedx.org/bom` checks the archive's SBOM attestation instead: the SBOM says what the
release is made of.
