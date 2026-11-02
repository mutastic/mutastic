# Releasing mutastic

Every library module is published to Maven Central as `io.mutastic:<module>`, signed. The CLI's
distribution is attached to the GitHub release, and the Gradle plugin also goes to the Gradle Plugin
Portal, once its release is on Central. A release starts when a GitHub release is
published (`.github/workflows/release.yml`). It uploads to the Central Portal but doesn't publish:
you check the upload and publish it by hand. Until then it can be dropped. Once published, a
version can never be changed or removed.

## Once

1. **The namespace.** On [central.sonatype.com](https://central.sonatype.com), go to *Namespaces*,
   then *Add Namespace*, and enter `io.mutastic`. Add the verification key it shows as a DNS TXT
   record on `mutastic.io`, then press *Verify*.
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
   account, so tictactoe's works here too, and generating a new one may retire the old.
5. **The Gradle Plugin Portal.** Sign in at [plugins.gradle.org](https://plugins.gradle.org), and
   on your profile's *API Keys* tab generate a key and secret:
   ```sh
   gh secret set GRADLE_PUBLISH_KEY --env release --repo mutastic/mutastic
   gh secret set GRADLE_PUBLISH_SECRET --env release --repo mutastic/mutastic
   ```
   The portal reviews a plugin's first version by hand, usually in a few days, and asks for proof
   of `mutastic.io` with a DNS TXT record. Its DNS is at Namecheap: *Advanced DNS*, a TXT record
   on host `@`, beside the Central one. Keep both.
6. **A signed dry run,** to check the key works before a real release:
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

1. With `main` green, create the release; its tag gives the version:
   ```sh
   gh release create v0.1.0 --target main --generate-notes
   ```
2. Approve the `release` job when GitHub asks. It builds, uploads, and attaches the CLI.
3. In the Central Portal, under *Deployments*, wait for the deployment to show *Validated*, look
   at what's in it, then press *Publish*, or *Drop* to throw it away. It reaches Maven Central
   within about half an hour.
4. Once it's on Central, send the Gradle plugin to the portal, and approve the job:
   ```sh
   gh workflow run portal.yml -f tag=v0.1.0
   ```
   It checks the release is on Central first: the plugin's jars come from there. A plugin's first
   version waits for the portal's review before it's listed.

If validation fails, drop the deployment, fix the cause, delete the GitHub release and its tag, and
release again. Nothing reached Central, so the version is still free.
