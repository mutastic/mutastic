# Releasing mutastic

Every library module is published to Maven Central as `io.mutastic:<module>`, signed. The CLI's
distribution is attached to the GitHub release. A release starts when a GitHub release is
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
   gpg --quick-gen-key "mutastic releases <your email>" rsa4096 sign 3y
   gpg --list-secret-keys --keyid-format short   # the key id is after rsa4096/, 8 hex digits
   KEY=<key id>
   # Central checks signatures against public keyservers.
   gpg --keyserver keyserver.ubuntu.com --send-keys $KEY
   gpg --keyserver keys.openpgp.org --send-keys $KEY
   ```
   Back up the private key and its revocation certificate (`~/.gnupg/openpgp-revocs.d/`), for
   example in a password manager.
4. **The `release` environment and its secrets.** Add yourself as a required reviewer in the repo's
   *Settings → Environments → release*, so no tag reaches Central without you.
   ```sh
   gh api -X PUT repos/mutastic/mutastic/environments/release
   # The private key goes straight from gpg to GitHub: it's never printed.
   gpg --armor --export-secret-keys $KEY | gh secret set SIGNING_KEY --env release --repo mutastic/mutastic
   gh secret set SIGNING_KEY_ID --env release --repo mutastic/mutastic --body $KEY
   # These prompt, so the values stay out of shell history.
   gh secret set SIGNING_KEY_PASSWORD --env release --repo mutastic/mutastic
   gh secret set MAVEN_CENTRAL_USERNAME --env release --repo mutastic/mutastic
   gh secret set MAVEN_CENTRAL_PASSWORD --env release --repo mutastic/mutastic
   ```
5. **A signed dry run,** to check the key works before a real release:
   ```sh
   read -s "PASS?key password: "
   ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys $KEY)" \
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

If validation fails, drop the deployment, fix the cause, delete the GitHub release and its tag, and
release again. Nothing reached Central, so the version is still free.
