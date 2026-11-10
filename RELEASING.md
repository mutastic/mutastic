# Releasing mutastic

Every library module is published to Maven Central as `io.mutastic:<module>`, signed. The CLI's
distribution is attached to the GitHub release, and the Gradle plugin also goes to the Gradle Plugin
Portal, once its release is on Central. The tag also deploys that release's docs to
[docs.mutastic.io](https://docs.mutastic.io). A release starts when a GitHub release is
published (`.github/workflows/release.yml`). It uploads to the Central Portal but doesn't publish:
you check the upload and publish it by hand. Until then it can be dropped. Once published, a
version can never be changed or removed.

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
   events format; these are left to you:
   - **Scores.** Read the release notes' *Scoring changes*. A patch release (`X.Y.Z+1`) has none:
     anything that moves a score (an operator into or out of the default set, a filter that skips
     more or less) waits for a minor release. A minor release's notes say which scores move, and
     how.
   - **Ids.** Run `./gradlew :mutastic-cli:exportIds`. If `src/test/ids/ids.tsv` changes, it's an
     operator new since the last release: commit it, so its ids are held from this release on.
   - **The Rust edition.** From 1.0.0 the editions release each minor version together: for
     `X.Y.0`, the Rust edition's `X.Y.0` is ready to publish beside this one (`RELEASING.md` in
     `mutastic/mutastic-rs`), as the native `mutastic X.Y.z` runs the newest Java `X.Y.*`. A patch
     (`X.Y.Z+1`) may ship alone: everyone on the native `X.Y.*` gets it within a day, once its
     GitHub release is published. Until the CLI is attached, the native `mutastic` keeps running
     the patch it has.
   - **The Rust docs pin.** The docs a tag deploys show the Rust edition's pages from
     `docs/rust-edition.json` as it is at that tag, for as long as that release line is published,
     so move the pin before tagging, not after. For `X.Y.0`: once the Rust edition's `vX.Y.0` tag
     is pushed (its release can stay a draft), set `ref` to it. A shared page whose mutastic-rs
     source changed since its stamp stops the tag's docs build (`scripts/check-editions.mjs`):
     bring each such page's Rust side up to date, then stamp it, and only after the pin moves, as
     CI reads the pin: `MUTASTIC_RS_DOCS=pinned npm run stamp-shared -- <page> …` in `docs/`.
     Commit both with `main` green. Publish the Rust draft only once
     this release's GitHub release has its CLI attached: the native `mutastic X.Y.0` finds nothing
     to run until then.
   - **Files on Central.** Central counts every file against a monthly limit
     ([Usage](https://central.sonatype.com/publishing/usage)). `checkCentralFiles` keeps a release
     small enough for four a month; look at the usage before a second release in a month.
2. With `main` green, create the release; its tag gives the version:
   ```sh
   gh release create vX.Y.Z --target main --generate-notes
   ```
3. Approve the `release` job when GitHub asks. It builds, uploads, and attaches the CLI.
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
7. From 1.0.0 on, hold the next release to this one: its plugin API, events format and rules files.
   Before 1.0.0 skip this step: anything may change, and the checks have nothing to compare with.
   Once the release is on Central, set `lastStableRelease` in `gradle.properties` to its version,
   so `checkApiCompatibility` compares the plugin API with its jar, and keep its events schema and
   rules files beside it, so `SchemaCompatibilityTest` and `RulesFileFormatTest` compare with them
   ([Schemas and stability](docs/src/content/docs/reference/schemas.mdx) says what counts as
   breaking):
   ```sh
   V=X.Y.Z
   cp docs/public/schemas/events/v1.json mutastic-report/src/test/resources/io/mutastic/report/released/events-v1.json
   R=mutastic-cli/src/test/resources/rules
   git rm -rq $R; mkdir -p $R/$V
   cp mutastic-engine/src/main/resources/io/mutastic/engine/streams.yaml docs/src/data/plugins/*.yaml $R/$V/
   ./gradlew :mutastic-cli:exportIds   # writes each rules file's .read beside it
   ```
   Commit them together. Until a release does, a field added since the last one can still change
   or go.

If validation fails, drop the deployment, fix the cause, delete the GitHub release and its tag, and
release again. Nothing reached Central, so the version is still free.
