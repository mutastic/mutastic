#!/usr/bin/env bash
# Prints what was running when a CLI test ran out of time: the dumps TimeoutDump wrote, each
# test's threads, the processes it started and every other JVM's threads. In the log, since a
# runner that has gone wrong may not upload the reports they're also in.
#
#   .github/timeout-dumps.sh
dumps=mutastic-cli/build/reports/timeouts
if ! ls "$dumps"/*.txt > /dev/null 2>&1; then
  echo "No test ran out of time."
  exit 0
fi
for dump in "$dumps"/*.txt; do
  echo "::group::$(head -n 1 "$dump")"
  cat "$dump"
  echo "::endgroup::"
done
echo "::error::$(ls "$dumps"/*.txt | wc -l | tr -d ' ') test(s) ran out of time; what was running is above."
