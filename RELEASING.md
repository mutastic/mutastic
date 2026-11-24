# Releasing mutastic

Every library module is published to Maven Central as `io.mutastic:<module>`, signed. The CLI's
distribution and a CycloneDX SBOM are attached to the GitHub release, each attested
([Checking a download](#checking-a-download)), and the Gradle plugin also goes to the Gradle Plugin
Portal, once its release is on Central. The tag also deploys that release's docs to
[docs.mutastic.io](https://docs.mutastic.io). A release starts when a GitHub release is
published (`.github/workflows/release.yml`). It uploads to the Central Portal but doesn't publish:
you check the upload and publish it by hand. Until then it can be dropped. Once published, a
version can never be changed or removed.

## The release train

From 1.4.0, a minor release leaves every second Tuesday, both editions together for `X.Y.0`, this
one first, then the Rust edition's, whose
[RELEASING.md](https://github.com/mutastic/mutastic-rs/blob/main/RELEASING.md#the-release-train)
has this same section. Tuesday is a proposal: the day is Brian's to change. A change that misses a
train waits for the next one.

- **The cut-off.** A train takes the `changes/` entries merged by the day before it.
- **The gates.** It leaves only when mutastic-rs's nightly corpus (`corpus.yml`) is green, the
  Java edition's corpus to come; `scripts/release-check.sh X.Y.0` passes in both editions
  ([Each release](#each-release)); and the candidate run passes (`candidate-run.yml`,
  [Before tagging](#before-tagging)).
- **A release candidate,** `vX.Y.0-rc1`, is a GitHub pre-release only, never a Central release:
  each one costs about 244 files of Central's monthly budget. The native `mutastic` already skips
  `-rc` tags, as they aren't plain versions (mutastic-rs's RELEASING.md,
  [A bad Java patch](https://github.com/mutastic/mutastic-rs/blob/main/RELEASING.md#a-bad-java-patch)),
  so nobody is moved onto one. Publishing it starts `release.yml`: reject its `release` approval
  if it asks. The job takes only a `vX.Y.Z` tag, so it would upload nothing anyway.
- **Patches,** between trains, only for a regression or a security fix, never a feature.
- **Central's budget.** Before a second release in a month, check the
  [Usage Center](https://central.sonatype.com/publishing/usage). Before an urgent release that
  would go over a limit, write to central-support first.
- **Who decides.** Brian decides a patch. Agents never cut a release on their own: when asked,
  they prepare the release commit.

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
4. **The `release` environment and its secrets.** Add yourself as a required reviewer in the repo's
   *Settings → Environments → release*, so no tag reaches Central without you.
   ```sh
   gh api -X PUT repos/mutastic/mutastic/environments/release
   # The private key goes straight from gpg to GitHub: it's never printed.
   gpg --armor --export-secret-keys $KEY | gh secret set SIGNING_KEY --env release --repo mutastic/mutastic
   gh secret set SIGNING_KEY_ID --env release --repo mutastic/mutastic --body $KEY   # the short id
   # These prompt, so the values stay out of shell history.
   gh secret set SIGNING_KEY_PASSWORD --env release --repo mutastic/mutastic    # the key's passphrase
   gh secret set MAVEN_CENTRAL_USERNAME --env release --repo mutastic/mutastic  # the token's username, from step 2
   gh secret set MAVEN_CENTRAL_PASSWORD --env release --repo mutastic/mutastic  # the token's password, from step 2
   ```
   The token is not your Central Portal login, and the portal shows it once. Tokens belong to the
   account, not the namespace, and generating a new one may retire the old.
5. **The Gradle Plugin Portal.** Sign in at [plugins.gradle.org](https://plugins.gradle.org), and
   on your profile's *API Keys* tab generate a key and secret:
   ```sh
   gh secret set GRADLE_PUBLISH_KEY --env release --repo mutastic/mutastic
   gh secret set GRADLE_PUBLISH_SECRET --env release --repo mutastic/mutastic
   ```
   The portal reviews a plugin's first version by hand, usually in a few days, and asks for proof
   of `mutastic.io` with a DNS TXT record. Its DNS is at Cloudflare: *mutastic.io → DNS →
   Records*, a TXT record on `@`, beside the Central one. Keep both.
6. **The docs site.** A Cloudflare Pages project and two repository secrets, so the docs workflow
   can deploy: [docs/README.md](docs/README.md) has the steps.
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

## Each release

1. **Check the promises** ([Compatibility](docs/src/content/docs/reference/compatibility.mdx)).
   The build already fails on a moved mutant id, a break in the plugin API and a break in the
   events format. `scripts/release-check.sh X.Y.Z` checks what a release needs beyond that, all at
   once, and lists what's wrong: the changelog's rules, the ids, the Rust docs pin and the shared
   pages' stamps, and the files on Central. It writes nothing tracked and publishes nothing, so run
   it as often as you like; CI runs it on the release pull request too, the part that needs no
   build in Commits and the ids and Central in CI's merge queue run. What it can't judge is left to
   you:
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
     Until the CLI is attached, the native `mutastic` keeps running the patch it has.
   - **The Rust docs pin.** The docs a tag deploys show the Rust edition's pages from
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
     tag's docs build. A stamp says the page is up to date, so bring each page it lists up to date
     with the Rust edition, from the diff it prints, before you commit. Commit the pin, the stamps
     and the pages with `main` green; `release-check` fails while the pin isn't an `X.Y.*` or a
     stamp is stale. The Rust edition's
     `vX.Y.0` is tagged only once this release's GitHub release has its CLI attached: its release
     workflow builds nothing until then, as the native `mutastic X.Y.0` would find nothing to run.
   - **Files on Central.** Central counts every file against a monthly limit
     ([Usage](https://central.sonatype.com/publishing/usage)). `checkCentralFiles` keeps a release
     small enough for four a month, and `release-check` runs it; look at the usage before a second
     release in a month.
2. **The release's notes.** Each entry since the last release is a file in `changes/`
   ([changes/README.md](changes/README.md)), so pull requests don't conflict over `CHANGELOG.md`.
   Read them as one section, then gather them into `CHANGELOG.md` as `## X.Y.Z (D Mon YYYY)`,
   dated today, which deletes the files:
   ```sh
   scripts/changelog.sh preview X.Y.Z   # prints the section, writes nothing
   scripts/changelog.sh release X.Y.Z   # writes it below Unreleased, deletes the entries
   ```
   Edit the section if it needs it, a paragraph above a section's entries or one at the top, and
   commit it with the deleted files as `chore(release): version X.Y.Z`: only a commit with the
   `release` scope may edit `CHANGELOG.md`. With `main` green and nothing wrong, create the release
   from that section; its tag gives the version:
   ```sh
   scripts/release-check.sh X.Y.Z                        # says it's ready, or lists what isn't
   scripts/changelog.sh notes X.Y.Z > notes.md           # the section, without its heading
   gh release create vX.Y.Z --target main --notes-file notes.md
   ```
   The release workflow reads the same section: a release whose tag has none stops before
   anything is uploaded, and one created without notes is given them.
3. Approve the `release` job when GitHub asks. Before it asks, the workflow checks the tag's
   commit is on `main` and that CI passed on it, in the merge queue or on the push to `main`, and
   stops otherwise, naming the run that's missing or red: tag only a commit `main` has, once it's
   green. The tests aren't run again, as the queue ran them on that very tree. The job builds what
   ships and checks what only a release can (the plugin API, the ids, the files on Central and the
   plugin for the portal), then uploads: a few minutes, where the whole build on one runner took
   52 for 1.3.1. A job without the secrets then attests the CLI's archives, `SHA256SUMS` and the
   SBOM, and attaches them. For `X.Y.0`, the Rust edition's `vX.Y.0` is tagged now, not before:
   its release workflow builds nothing until this release has its CLI.
4. In the Central Portal, under *Deployments*, wait for the deployment to show *Validated*, look
   at what's in it, then press *Publish*, or *Drop* to throw it away. It reaches Maven Central
   within about half an hour.
5. Once it's on Central, send the Gradle plugin to the portal, and approve the job:
   ```sh
   gh workflow run portal.yml --ref vX.Y.Z
   ```
   It runs on the tag, as the `release` environment only lets tags deploy, and checks the release
   is on Central first: the plugin's jars come from there. A plugin's first
   version waits for the portal's review before it's listed.
6. Check [docs.mutastic.io](https://docs.mutastic.io): the tag's push deploys it, with no approval
   needed. The root and `/vX.Y/` should both be the new release, with no banner saying the docs
   describe `main`. `mutastic --help` links to `/vX.Y/`, so it has to exist. To deploy it again,
   every release line rebuilt from its own tag:
   ```sh
   gh workflow run docs.yml --ref main -f release=true
   ```
   Check the schemas are served at their `$id`s too:
   [/schemas/events/v1.json](https://docs.mutastic.io/schemas/events/v1.json).
7. From 1.0.0 on, hold the next release to this one: its plugin API, events format, rules files,
   `mutastic.yaml` and command line.
   Before 1.0.0 skip this step: anything may change, and the checks have nothing to compare with.
   Once the release is on Central, hold it, on a branch from `main`:
   ```sh
   scripts/hold-release.sh X.Y.Z   # refuses until X.Y.Z's mutastic-api jar is on Central
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
   the last release. Add the ones this release added, as #183 did for 1.3.0, and run the script
   again for their `.read`. `scripts/test-hold-release.sh`, in CI's build job, checks the script
   still writes #183's tree.

   Commit them together as `build: hold the next release to X.Y.Z`, in a pull request of its own.
   Until a release does, a field added since the last one can still change or go. (Later, the
   publish job will run the script once Central has the release and open that pull request
   itself; until then it's run by hand.)

If validation fails, drop the deployment and fix the cause. Nothing reached Central, but don't
delete the GitHub release and tag it again: once releases are immutable, a tag can't be used twice,
and anyone who downloaded its assets has them. Release `X.Y.Z+1` instead.

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
expects. It starts on its own when the release is published and waits up to four hours for the
steps above that are done by hand. Once `portal.yml` has run, look at it in *Actions*; to check a
release again, or once a wait ran out:
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
