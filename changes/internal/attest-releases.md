- **Every asset of a release is attested, and checked against its attestation once it's out.** The
  release workflow attests the CLI's zip and tar, `SHA256SUMS` and a CycloneDX SBOM as built by
  `release.yml` at the release's tag, and the SBOM as what the archives are made of. The SBOM is
  attached to the GitHub release, not sent to Central, whose files count against a monthly limit.
  The job that writes to the repository and signs the attestations has no publishing secrets, and
  the job with them can't write. `release-verify.yml` runs `gh attestation verify` on every asset
  it downloads, and RELEASING.md's *Checking a download* says how to check one.
