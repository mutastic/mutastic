#!/bin/sh
# Installs the `mutastic` command: curl -fsSL https://mutastic.io/install.sh | sh
#
# Downloads a release from GitHub into ~/.mutastic and links the command into ~/.local/bin. Run it
# again to update; nothing is installed outside those two directories, so removing them uninstalls
# it. mutastic itself runs on JDK 25, found through JAVA_HOME or the PATH.
#
# Settings, each optional:
#   MUTASTIC_VERSION   the release to install, 0.3.0; the latest unless set
#   MUTASTIC_HOME      where releases go, ~/.mutastic unless set
#   MUTASTIC_BIN_DIR   where the command is linked, ~/.local/bin unless set
#
# The source is install.sh in github.com/mutastic/mutastic; mutastic.io serves a copy of it.
#
# Everything is in functions, called on the last line, so a download cut short runs nothing.

set -eu

REPOSITORY="https://github.com/mutastic/mutastic"

say() {
  printf 'mutastic: %s\n' "$*"
}

fail() {
  printf 'mutastic: %s\n' "$*" >&2
  exit 1
}

need() {
  command -v "$1" > /dev/null 2>&1 || fail "the installer needs $1, which isn't on the PATH"
}

# The newest release's version, from where GitHub's "latest release" page redirects to.
latest() {
  url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$REPOSITORY/releases/latest") ||
    fail "couldn't reach $REPOSITORY to find the latest release"
  version=${url##*/v}
  case "$version" in
    [0-9]*.[0-9]*.[0-9]*) printf '%s\n' "$version" ;;
    *) fail "couldn't tell the latest release from $url" ;;
  esac
}

# Checks the download against the release's SHA256SUMS, when the release has one.
verify() {
  file=$1 sums=$2
  if ! curl -fsSL -o "$sums" "$REPOSITORY/releases/download/v$VERSION/SHA256SUMS" 2> /dev/null; then
    say "this release has no checksums to check the download against"
    return
  fi
  expected=$(grep " mutastic-$VERSION.tar\$" "$sums" | cut -d ' ' -f 1)
  [ -n "$expected" ] || fail "SHA256SUMS doesn't list mutastic-$VERSION.tar"
  if command -v sha256sum > /dev/null 2>&1; then
    actual=$(sha256sum "$file" | cut -d ' ' -f 1)
  else
    actual=$(shasum -a 256 "$file" | cut -d ' ' -f 1)
  fi
  [ "$actual" = "$expected" ] || fail "the download doesn't match its checksum: not installed"
}

# Says what to do when the JDK found isn't one mutastic runs on. Never fails: a JDK can come later.
check_java() {
  java=${JAVA_HOME:+$JAVA_HOME/bin/}java
  if ! command -v "$java" > /dev/null 2>&1; then
    say "no java found: mutastic runs on JDK 25, through JAVA_HOME or the PATH"
    return
  fi
  feature=$("$java" -XshowSettings:properties -version 2>&1 |
    sed -n 's/^ *java\.specification\.version = //p')
  case "$feature" in
    '' | *[!0-9]*) return ;;
  esac
  if [ "$feature" -lt 25 ]; then
    say "java is JDK $feature: mutastic runs on JDK 25, so set JAVA_HOME to one"
  fi
}

main() {
  need curl
  need tar
  VERSION=${MUTASTIC_VERSION:-$(latest)}
  home=${MUTASTIC_HOME:-$HOME/.mutastic}
  bin=${MUTASTIC_BIN_DIR:-$HOME/.local/bin}

  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  say "downloading $VERSION"
  curl -fsSL -o "$work/mutastic.tar" \
    "$REPOSITORY/releases/download/v$VERSION/mutastic-$VERSION.tar" ||
    fail "couldn't download $VERSION: is it a release? ($REPOSITORY/releases)"
  verify "$work/mutastic.tar" "$work/SHA256SUMS"

  mkdir -p "$home" "$bin"
  rm -rf "$home/mutastic-$VERSION"
  tar -xf "$work/mutastic.tar" -C "$home"
  [ -x "$home/mutastic-$VERSION/bin/mutastic" ] || fail "the download had no mutastic command in it"
  ln -sf "$home/mutastic-$VERSION/bin/mutastic" "$bin/mutastic"

  say "installed $VERSION: $bin/mutastic"
  check_java
  case ":$PATH:" in
    *":$bin:"*) say "next, in a Gradle or Maven project: mutastic run" ;;
    *)
      say "$bin isn't on your PATH: add it, then open a new terminal"
      path_line "$bin"
      say "then, in a Gradle or Maven project: mutastic run"
      ;;
  esac
}

# The line that puts `bin` on the PATH, for the shell the person uses: zsh, as macOS starts with,
# reads ~/.zshrc; bash ~/.bashrc; fish has its own command.
path_line() {
  case "${SHELL##*/}" in
    zsh) printf "  echo 'export PATH=\"%s:\$PATH\"' >> ~/.zshrc\n" "$1" ;;
    bash) printf "  echo 'export PATH=\"%s:\$PATH\"' >> ~/.bashrc\n" "$1" ;;
    fish) printf '  fish_add_path %s\n' "$1" ;;
    *) printf '  export PATH="%s:$PATH"   (in your shell'"'"'s startup file)\n' "$1" ;;
  esac
}

main "$@"
