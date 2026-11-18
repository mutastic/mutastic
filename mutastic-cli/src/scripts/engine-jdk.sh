# mutastic runs on JDK 25 or later. The project's build runs on the JDK it was given, which may
# be older, and often must be: an older Gradle, or a Mockito, that doesn't know JDK 25. So this
# finds a JDK for mutastic alone: MUTASTIC_JAVA_HOME when it's set; else the newest JDK 25 or later
# of JAVA_HOME and those installed where JDKs are kept, as a JDK reads class files only as new as
# itself, and the project's may be newer than 25; else the java on the PATH, if it's 25 or later.
# JAVA_HOME names it for the rest of this script, and MUTASTIC_BUILD_JAVA_HOME keeps the JAVA_HOME
# given, empty for none, which mutastic gives back to the build and its tests.
MUTASTIC_BUILD_JAVA_HOME=${JAVA_HOME-}
export MUTASTIC_BUILD_JAVA_HOME

# The feature version of the JDK at $1: 25 for 25.0.1, 8 for 1.8.0. From its release file, which
# every JDK since 9 has, so no JVM starts.
mutastic_feature() {
    sed -n 's/^JAVA_VERSION="\(1\.\)\{0,1\}\([0-9][0-9]*\).*/\2/p' "$1/release" 2> /dev/null | head -n 1
}

# Whether the JDK at $1 is one mutastic runs on.
mutastic_runs_on() {
    [ -x "$1/bin/java" ] && [ "$(mutastic_feature "$1")" -ge 25 ] 2> /dev/null
}

# The newest JDK 25 or later among the directories given, each a JDK or a macOS bundle of one: a
# full JDK before a runtime of the same version, as a plugin's operators may compile code.
mutastic_newest() {
    mutastic_best= mutastic_best_rank=0
    for mutastic_candidate in "$@"; do
        [ -d "$mutastic_candidate/Contents/Home" ] && mutastic_candidate=$mutastic_candidate/Contents/Home
        if mutastic_runs_on "$mutastic_candidate"; then
            mutastic_rank=$(($(mutastic_feature "$mutastic_candidate") * 2))
            [ -x "$mutastic_candidate/bin/javac" ] && mutastic_rank=$((mutastic_rank + 1))
            if [ "$mutastic_rank" -gt "$mutastic_best_rank" ]; then
                mutastic_best=$mutastic_candidate mutastic_best_rank=$mutastic_rank
            fi
        fi
    done
    [ -n "$mutastic_best" ] && printf '%s\n' "$mutastic_best"
}

mutastic_engine=
if [ -n "${MUTASTIC_JAVA_HOME-}" ]; then
    if ! mutastic_runs_on "$MUTASTIC_JAVA_HOME"; then
        echo "mutastic: MUTASTIC_JAVA_HOME is $MUTASTIC_JAVA_HOME, which isn't a JDK 25 or later: point it at one, or unset it for mutastic to find one" >&2
        exit 2
    fi
    mutastic_engine=$MUTASTIC_JAVA_HOME
else
    mutastic_macos=
    [ -x /usr/libexec/java_home ] && mutastic_macos=$(/usr/libexec/java_home -v 25+ 2> /dev/null)
    mutastic_data=${XDG_DATA_HOME:-$HOME/.local/share}
    mutastic_engine=$(mutastic_newest \
        "${JAVA_HOME-}" \
        ${mutastic_macos:+"$mutastic_macos"} \
        "${SDKMAN_DIR:-$HOME/.sdkman}"/candidates/java/* \
        "${ASDF_DATA_DIR:-$HOME/.asdf}"/installs/java/* \
        "${MISE_DATA_DIR:-$mutastic_data/mise}"/installs/java/* \
        "${GRADLE_USER_HOME:-$HOME/.gradle}"/jdks/* \
        "${GRADLE_USER_HOME:-$HOME/.gradle}"/jdks/*/* \
        "$HOME"/.jdks/* \
        /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk \
        /usr/local/opt/openjdk*/libexec/openjdk.jdk \
        /Library/Java/JavaVirtualMachines/* \
        /usr/lib/jvm/* \
        /usr/java/* \
        /opt/java/*)
    # A java on the PATH from none of those places: asked last, as asking starts a JVM.
    if [ -z "$mutastic_engine" ] && command -v java > /dev/null 2>&1; then
        mutastic_path_home=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p')
        mutastic_runs_on "$mutastic_path_home" && mutastic_engine=$mutastic_path_home
    fi
fi
if [ -z "$mutastic_engine" ]; then
    cat >&2 << 'EOF'
mutastic: mutastic runs on JDK 25 or later, and found none. It looked at MUTASTIC_JAVA_HOME, JAVA_HOME, the java on the PATH, and where SDKMAN, asdf, mise, Gradle, IntelliJ, Homebrew, macOS and Linux keep JDKs.
Install a JDK 25, or point mutastic at one. Your build keeps its own JAVA_HOME:
  export MUTASTIC_JAVA_HOME=<a JDK 25 or later>
EOF
    exit 2
fi
JAVA_HOME=$mutastic_engine
