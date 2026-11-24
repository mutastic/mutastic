#!/bin/sh
# Holds the next release to this one (RELEASING.md, step 7): once X.Y.Z is on Maven Central, the
# compatibility checks compare the build with what X.Y.Z released.
#
#   scripts/hold-release.sh X.Y.Z                       refuses until X.Y.Z is on Maven Central
#   scripts/hold-release.sh --offline-check-skip X.Y.Z  doesn't ask Central, for tests
#
# It sets lastStableRelease in gradle.properties, for checkApiCompatibility, which downloads that
# release's mutastic-api jar from Central. It keeps the release's schemas (SchemaCompatibilityTest),
# its rules files (RulesFileFormatTest), its mutastic.yaml files (ConfigurationFormatTest) and its
# commands.json (CommandLineCompatibilityTest), replacing the last release's, and runs exportIds,
# which writes how each rules file and mutastic.yaml reads beside it. Then commit what it changed.
#
# Each file is taken from the tag vX.Y.Z, not the working tree: a pull request merged after the
# tag must not become part of what was released.
#
# The every-key mutastic.yaml is the last release's, with any key this one added. Adding a key is
# a person's edit, so the script copies the last release's file and lists the schema's keys the
# file doesn't use; once the file is in place for X.Y.Z, it is kept, and running the script again
# changes nothing.

set -eu
cd "$(dirname "$0")/.."

usage="usage: scripts/hold-release.sh [--offline-check-skip] X.Y.Z"
ask_central=true
version=
for arg in "$@"; do
  case $arg in
    --offline-check-skip) ask_central=false ;;
    -*) echo "$usage" >&2; exit 2 ;;
    *)
      if [ -n "$version" ]; then echo "$usage" >&2; exit 2; fi
      version=$arg
      ;;
  esac
done
case $version in
  '' | *[!0-9.]* | .* | *. | *..*) echo "$usage" >&2; exit 2 ;;
esac
if [ "$(echo "$version" | tr -cd . | wc -c)" -ne 2 ]; then
  echo "$usage" >&2
  exit 2
fi

tag=v$version
schemas=mutastic-report/src/test/resources/io/mutastic/report/released
rules=mutastic-cli/src/test/resources/rules
released=mutastic-cli/src/test/resources/released
every_key=$released/$version/every-key/mutastic.yaml

if ! git rev-parse -q --verify "refs/tags/$tag" > /dev/null; then
  echo "There is no tag $tag here: fetch it (git fetch origin tag $tag), or release $version first." >&2
  exit 1
fi

# checkApiCompatibility downloads this jar, so the hold waits until Central serves it.
if $ask_central; then
  jar=https://repo1.maven.org/maven2/io/mutastic/mutastic-api/$version/mutastic-api-$version.jar
  if ! curl -fsSI --retry 3 "$jar" > /dev/null; then
    echo "$version isn't on Maven Central yet ($jar): hold it once it's published." >&2
    exit 1
  fi
fi

# The every-key file to start from: this release's, if it's already here, or the last release's.
if [ -f "$every_key" ]; then
  start=
else
  start=$(ls "$released"/*/every-key/mutastic.yaml 2> /dev/null | grep -v "^$released/$version/" || true)
  if [ -z "$start" ] || [ "$(echo "$start" | wc -l)" -ne 1 ]; then
    echo "Expected one last release's every-key file in $released, found: ${start:-none}" >&2
    exit 1
  fi
fi

last=$(sed -n 's/^lastStableRelease=//p' gradle.properties)

# Writes a file as the tag has it.
released_file() {
  mkdir -p "$(dirname "$2")"
  git show "$tag:$1" > "$2"
}

properties=$(mktemp)
sed "s/^lastStableRelease=.*/lastStableRelease=$version/" gradle.properties > "$properties"
cat "$properties" > gradle.properties
rm -f "$properties"

for schema in events mutastic-yaml rules; do
  released_file "docs/public/schemas/$schema/v1.json" "$schemas/$schema-v1.json"
done

# Each release keeps only its own rules files, so the last release's go.
rm -rf "$rules"
released_file mutastic-engine/src/main/resources/io/mutastic/engine/streams.yaml "$rules/$version/streams.yaml"
for plugin in $(git ls-tree --name-only "$tag" docs/src/data/plugins/ | grep '\.yaml$'); do
  released_file "$plugin" "$rules/$version/$(basename "$plugin")"
done

# And only its own command line and mutastic.yaml files.
if [ -n "$start" ]; then
  mkdir -p "$(dirname "$every_key")"
  cp "$start" "$every_key"
fi
for directory in "$released"/*/; do
  if [ "$directory" != "$released/$version/" ]; then rm -rf "$directory"; fi
done
released_file docs/src/data/commands.json "$released/$version/commands.json"
released_file samples/configured/mutastic.yaml "$released/$version/configured/mutastic.yaml"

# Writes each rules file's and mutastic.yaml's .read beside it, and src/test/ids/ids.tsv.
./gradlew --quiet :mutastic-cli:exportIds

# The key paths a mutastic.yaml file uses (rules[].skip, gate.operators): each key's path is its
# parents' keys, with [] after one whose value is a list of maps.
yaml_keys() {
  awk '
    /^[[:space:]]*(#|$)/ { next }
    {
      match($0, /^ */); indent = RLENGTH; line = substr($0, indent + 1)
      item = line ~ /^- /
      if (item) { line = substr(line, 3); indent += 2 }
      if (line !~ /^[A-Za-z0-9_-]+:( |$)/) next
      key = line; sub(/:.*/, "", key)
      while (depth > 0 && column[depth] >= indent) depth--
      if (item && depth > 0) prefix[depth] = path[depth] "[]"
      parent = depth > 0 ? prefix[depth] "." : ""
      depth++; column[depth] = indent; path[depth] = parent key; prefix[depth] = parent key
      print path[depth]
    }
  ' "$1" | sort -u
}

# The key paths the mutastic.yaml schema allows, following $ref, oneOf and items.
schema_keys() {
  jq -r '
    def deref($root):
      if type == "object" and has("$ref")
      then (.["$ref"] | ltrimstr("#/") | split("/")) as $at | ($root | getpath($at)) + del(.["$ref"])
      else . end;
    def keys($root; $path):
      deref($root) as $schema
      | (($schema.properties // {}) | to_entries[]
          | ($path + [.key]) as $key | ($key, (.value | keys($root; $key)))),
        (($schema.oneOf // [])[] | keys($root; $path)),
        (if ($schema.items | type) == "object"
         then ($schema.items | keys($root; $path[:-1] + [$path[-1] + "[]"])) else empty end);
    . as $root | [keys($root; [])] | map(join(".")) | unique[]
  '
}

used=$(mktemp)
yaml_keys "$every_key" > "$used"
missing=$(schema_keys < "$schemas/mutastic-yaml-v1.json" | grep -vxF -f "$used" || true)
rm -f "$used"
new=
if [ -n "$last" ] && [ "$last" != "$version" ] && git rev-parse -q --verify "refs/tags/v$last" > /dev/null; then
  new=$(git show "v$last:docs/public/schemas/mutastic-yaml/v1.json" | schema_keys)
fi

echo "Held the next release to $version."
if [ -n "$missing" ]; then
  echo
  echo "$every_key doesn't use these keys of $version's mutastic.yaml schema:"
  echo "$missing" | while read -r key; do
    if [ -n "$new" ] && ! echo "$new" | grep -qxF "$key"; then
      echo "  $key   (new since $last)"
    else
      echo "  $key"
    fi
  done
  echo "Add the ones $version added, then run this again for their .read."
fi
echo
echo "Commit what changed:"
echo "  git add -A gradle.properties $schemas $rules $released mutastic-cli/src/test/ids"
