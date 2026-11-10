#!/bin/sh
# Installs the `mutastic` command, for Java, Kotlin and Rust projects alike, by running the
# installer mutastic.io serves:
#   curl -fsSL https://mutastic.io/install.sh | sh
#
# That installer is scripts/install.sh in github.com/mutastic/mutastic-rs. It installs the native
# `mutastic` into ~/.mutastic and links it into ~/.local/bin. The first time `mutastic` runs in a
# Maven or Gradle project, it fetches this repository's Java edition into ~/.mutastic, which needs
# JDK 25; a Rust project never needs it. This script stays here so that `sh install.sh`, from a
# clone, does the same. Its arguments and environment are passed through.
#
# MUTASTIC_VERSION still selects the Java edition's release, now when `mutastic` runs rather than
# when it's installed: with it set, `mutastic` uses that release in a Maven or Gradle project,
# fetching it if it isn't kept. Without it, `mutastic` uses the newest release it keeps, or fetches
# the latest. MUTASTIC_HOME and MUTASTIC_BIN_DIR choose the directories, as before.

set -eu

fail() {
  printf 'mutastic: %s\n' "$*" >&2
  exit 1
}

command -v curl > /dev/null 2>&1 || fail "the installer needs curl, which isn't on the PATH"

# Fetched whole before it runs, so a failed download stops here rather than running nothing.
installer=$(curl -fsSL https://mutastic.io/install.sh) ||
  fail "couldn't download the installer from https://mutastic.io/install.sh"
printf '%s\n' "$installer" | sh -s -- "$@"
