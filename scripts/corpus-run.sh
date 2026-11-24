#!/usr/bin/env bash
# One project of the corpus (corpus/corpus.yaml), run as the nightly runs it: cloned at its pinned
# commit, its own tests first, then a share of its mutants as events, then the same share for an
# agent. The samples show one feature each; the corpus holds the combinations people write, where
# the bugs that matter turned up.
#
#   scripts/corpus-run.sh NAME                       run one project, print its result line
#   scripts/corpus-run.sh --matrix [NAMES]           check corpus.yaml, print its entries as JSON
#   scripts/corpus-run.sh --summary DIR PREV NAME…   the results in DIR as a table, against PREV
#
# Corpus projects are other people's code, cloned and built: they run in GitHub Actions only.
# Outside it, this runs only an entry whose git is a file:// URL, a fixture standing in for a
# project, from a corpus file CORPUS_FILE names.
#
# A project passes when its expectation holds:
# - score: the run ends with run-finished, a score, at least one mutant, the reports it names
#   written and exit 0 (the threshold is 0: a project's own threshold isn't what's checked), with
#   no internal error and no "tests fail without any mutant"; and the agent run's head has the
#   agent format's shape.
# - refused:<code>: the run ends with run-failed and that code.
# - known:<bug>: it fails with the code in fails:, as the bug makes it today. Once it scores, it
#   fails the night saying so, so its expect moves to score with the fix.
# A project whose own tests fail is "project-red" and skipped, not failed, unless a refusal is what's
# expected (a red test is often why). Anything else fails, and exits 1. scripts/corpus.py judges.
#
# The result is one JSON line (name, expect, outcome, exit, mutants, score, seconds, got, reason,
# agent), printed and written to $RUNNER_TEMP/corpus/out/NAME.result.json (else $TMPDIR's), beside
# the runs' events and logs. The clone is in $RUNNER_TEMP/corpus/src/NAME.
#
# The build runs on JAVA_HOME, which the workflow sets to the entry's jdk, and mutastic on
# MUTASTIC_JAVA_HOME's JDK 25. CORPUS_LIMIT (900) is each mutastic run's time limit in seconds,
# CORPUS_TEST_LIMIT (900) the project's own tests'. MUTASTIC_BIN is the mutastic to run,
# mutastic-cli/build/install/mutastic/bin/mutastic (`just install`) by default.
set -u

cd "$(dirname "$0")/.." || exit 2
corpus=${CORPUS_FILE:-corpus/corpus.yaml}
limit=${CORPUS_LIMIT:-900}
test_limit=${CORPUS_TEST_LIMIT:-900}
base="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/corpus"
root=$PWD

# The judgements, in Python: scripts/corpus.py.
corpus_py() {
  python3 -I "$root/scripts/corpus.py" "$@"
}

die() {
  echo "corpus: $1" >&2
  exit 2
}

for tool in yq jq python3 timeout git; do
  command -v "$tool" > /dev/null || die "needs $tool on the PATH"
done
[ -f "$corpus" ] || die "no corpus file $corpus"

# The corpus as JSON, one entry to a line.
entries() {
  yq -o=json -I=0 '.[]' "$corpus"
}

# The project's own test command, as its build runs it: Gradle when there's a Gradle build (as
# mutastic picks it), else Maven; the wrapper when there is one. For a module, its tests and those
# of each --tests-from module.
own_tests() {
  local project=$1 module=$2 args=$3 gradle=gradle maven=mvn
  [ -x "$project/gradlew" ] && gradle=./gradlew
  [ -x "$project/mvnw" ] && maven=./mvnw
  local modules=()
  [ -n "$module" ] && modules+=("$module")
  local arg
  for arg in $args; do
    case $arg in --tests-from=*) modules+=("${arg#--tests-from=}") ;; esac
  done
  if compgen -G "$project/settings.gradle*" > /dev/null || compgen -G "$project/build.gradle*" > /dev/null; then
    if [ ${#modules[@]} -eq 0 ]; then
      echo "$gradle test"
    else
      printf '%s' "$gradle"
      printf ' %s:test' "${modules[@]}"
      echo
    fi
  elif [ ${#modules[@]} -eq 0 ]; then
    echo "$maven -B -ntp test"
  else
    local list
    list=$(printf '%s,' "${modules[@]#:}")
    echo "$maven -B -ntp test -am -pl ${list%,}"
  fi
}

# Clones NAME at its commit, runs its tests and mutastic on a share of it, and prints its result.
run_one() {
  local name=$1 entry
  entries | corpus_py matrix "$name" > /dev/null || exit 2
  entry=$(CORPUS_NAME=$name yq -o=json -I=0 '.[] | select(.name == strenv(CORPUS_NAME))' "$corpus")
  field() { jq -r ".$1 // \"\"" <<< "$entry"; }
  local git commit dir module args test shard expect
  git=$(field git) commit=$(field commit) dir=$(field dir) module=$(field module)
  args=$(jq -r '(.args // []) | join(" ")' <<< "$entry") test=$(field test)
  shard=$(field shard) expect=$(field expect)
  if [ "${GITHUB_ACTIONS:-}" != true ] && [[ "$git" != file://* ]]; then
    die "$name is someone else's code: corpus projects run in GitHub Actions only. Here, only an entry whose git is a file:// URL runs."
  fi

  local mutastic=${MUTASTIC_BIN:-mutastic-cli/build/install/mutastic/bin/mutastic}
  [ -x "$mutastic" ] || die "no mutastic at $mutastic: just install, or set MUTASTIC_BIN"
  mutastic=$(cd "$(dirname "$mutastic")" && pwd)/$(basename "$mutastic")
  local src=$base/src/$name out=$base/out
  rm -rf "$src" && mkdir -p "$src" "$out"
  out=$(cd "$out" && pwd)
  rm -f "$out/$name".*

  # The commit alone, not the history: GitHub serves any commit by its hash.
  echo "corpus: $name, $git at $commit" >&2
  if ! { git -C "$src" init -q && git -C "$src" fetch -q --depth=1 "$git" "$commit" &&
    git -C "$src" -c advice.detachedHead=false checkout -q FETCH_HEAD; } > "$out/$name.clone.log" 2>&1 ||
    [ "$(git -C "$src" rev-parse HEAD 2> /dev/null)" != "$commit" ]; then
    cat "$out/$name.clone.log" >&2
    die "couldn't fetch $commit from $git"
  fi
  local project=$src
  [ "$dir" = . ] || project=$src/$dir
  [ -d "$project" ] || die "$name: no directory $dir at $commit"

  # A project's misses aren't this repository's: no annotations or job summary from mutastic.
  export MUTASTIC_GITHUB=off

  [ -n "$test" ] || test=$(own_tests "$project" "$module" "$args")
  echo "corpus: $name's own tests, ${test}…" >&2
  local status red=""
  # shellcheck disable=SC2086 # the command, a word at a time
  (cd "$project" && timeout -k 30 "$test_limit" $test) > "$out/$name.own-tests.log" 2>&1
  status=$?
  if [ "$status" -ne 0 ] && [[ "$expect" != refused:* ]]; then
    red="its own \`$test\` fails (exit $status), so mutastic didn't run"
    [ "$status" -eq 124 ] && red="its own \`$test\` took over ${test_limit}s, so mutastic didn't run"
    tail -n 20 "$out/$name.own-tests.log" >&2
  fi

  local seconds=0 began agent_status=0
  : > "$out/$name.events.jsonl"
  : > "$out/$name.run.log"
  : > "$out/$name.agent.txt"
  if [ -z "$red" ]; then
    echo "corpus: $name, share 1/${shard} as events…" >&2
    began=$SECONDS
    # shellcheck disable=SC2086 # the module and args, a word each, or none
    (cd "$src" && timeout -k 30 "$limit" "$mutastic" run "$dir" $module $args --shard=1/"$shard" \
      --threshold=0 --format=jsonl) > "$out/$name.events.jsonl" 2> "$out/$name.run.log"
    status=$?
    seconds=$((SECONDS - began))
    if grep -q '"reason":"run-finished"' "$out/$name.events.jsonl"; then
      echo "corpus: $name, the same share for an agent…" >&2
      # shellcheck disable=SC2086
      (cd "$src" && MUTASTIC_AGENT=1 timeout -k 30 "$limit" "$mutastic" run "$dir" $module $args \
        --shard=1/"$shard" --threshold=0) > "$out/$name.agent.txt" 2> "$out/$name.agent.log"
      agent_status=$?
    fi
    # mutastic's logs of the build and the workers (Gradle's build/mutastic, Maven's
    # target/mutastic), beside the events, for a failure to be read from the artifact.
    local log
    while IFS= read -r log; do
      cp "$log" "$out/$name.$(basename "$log")"
    done < <(find "$project" -maxdepth 5 \( -path '*/build/mutastic/*.log' \
      -o -path '*/target/mutastic/*.log' \) 2> /dev/null)
  fi

  local line verdict
  line=$(corpus_py judge "$name" "$red" "$status" "$seconds" "$out/$name.events.jsonl" \
    "$out/$name.run.log" "$out/$name.agent.txt" "$agent_status" "$project" <<< "$entry")
  verdict=$?
  echo "$line" | tee "$out/$name.result.json"
  if [ "$verdict" -ne 0 ]; then
    tail -n 15 "$out/$name.run.log" | sed 's/^/    /' >&2
  fi
  return "$verdict"
}

case ${1:-} in
  --matrix) entries | corpus_py matrix "${2:-}" ;;
  --summary) shift && entries | corpus_py summary "$@" ;;
  "" | -*) die "scripts/corpus-run.sh NAME, --matrix [NAMES] or --summary DIR PREV NAME…" ;;
  *) run_one "$1" ;;
esac
