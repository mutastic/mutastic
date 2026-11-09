#!/usr/bin/env python3
"""Puts a module's shares back together, as one run's events, and checks its threshold on the whole.

The Nightly runs its longest module in shares (`--shard=N/M`, with --threshold=0, so no share is
held to the floor alone). This writes the events of the whole run, as one run would have: every
share's mutants, then a run-finished whose counts and score are the shares' added up, the score
worked out as the run's own is (caught and timed out over all but the skipped, Report#score), and
the threshold checked on it unrounded, as a run checks it.

It fails, with nothing passed, when a share is missing or didn't finish, when two shares ran the
same mutant (so they weren't shares of one run), or when a share didn't pass on its own: with a
threshold of 0 that's only a test that failed with no mutant in, which fails a whole run too.

Usage: shares.py MODULE THRESHOLD HISTORY_TSV SHARE_JSONL... > MODULE.jsonl
"""

import json
import math
import pathlib
import sys

COUNTS = ["Killed", "Survived", "Timeout", "NoCoverage", "Ignored"]
LISTS = ["flaky", "flakyEvidence", "likelyFlaky", "unrelatedCatches", "changedSinceLastRun",
         "reports"]


def events(path):
    if not pathlib.Path(path).is_file():
        sys.exit(f"{path}: missing: the share didn't run")
    return [json.loads(line) for line in pathlib.Path(path).read_text().splitlines() if line]


def finished(path, shown):
    found = [event for event in shown if event.get("reason") == "run-finished"]
    if len(found) != 1:
        sys.exit(f"{path}: no run-finished event: the share didn't finish")
    return found[0]


def rounded(value):
    """To one decimal place, half up, as the run's own events round it."""
    return None if value is None else math.floor(value * 10 + 0.5) / 10


def unique(items):
    """Each once, in the order first seen: the same stale ignore is in every share."""
    seen, kept = set(), []
    for item in items:
        key = json.dumps(item, sort_keys=True)
        if key not in seen:
            seen.add(key)
            kept.append(item)
    return kept


def previous_score(history, module):
    """The whole module's score last time, from the score history: each share compares only with
    its own last run, so none knows the whole's."""
    path = pathlib.Path(history)
    if not path.exists():
        return None
    rows = [line.split("\t") for line in path.read_text().splitlines()]
    scores = [row[rows[0].index("score")] for row in rows[1:]
              if row[rows[0].index("module")] == module]
    return float(scores[-1]) if scores and scores[-1] not in ("", "None") else None


def merged(module, threshold, history, paths):
    started, mutants, ends, ids = None, [], [], set()
    for path in paths:
        shown = events(path)
        end = finished(path, shown)
        if not end["passed"]:
            sys.exit(f"{path}: the share didn't pass on its own: a test fails with no mutant in")
        for event in shown:
            if event.get("reason") == "run-started":
                started = started or event
            elif event.get("reason") == "mutant-finished":
                if event["id"] in ids:
                    sys.exit(f"{path}: {event['id']} ran in two shares: these aren't one run's")
                ids.add(event["id"])
                mutants.append(event)
        ends.append(end)
    total = {key: sum(end[key] for end in ends) for key in COUNTS}
    scored = len(mutants) - total["Ignored"]
    detected = total["Killed"] + total["Timeout"]
    reached = detected + total["Survived"]
    score = 100.0 * detected / scored if scored else None
    whole = dict(ends[0])
    whole.update(total)
    whole.update({key.lower() if key != "NoCoverage" else "noCoverage": value
                  for key, value in total.items()})
    whole.update({
        "mutants": len(mutants),
        "score": rounded(score),
        "threshold": threshold,
        "passed": (100.0 if score is None else score) >= threshold,
        "coveredScore": rounded(100.0 * detected / reached) if reached else None,
        "tests": max(end["tests"] for end in ends),
        "previousScore": previous_score(history, module),
        "sameCodeAsLastRun": all(end.get("sameCodeAsLastRun") for end in ends),
        "sameMutasticAsLastRun": all(end.get("sameMutasticAsLastRun") for end in ends),
        "staleIgnores": unique(i for end in ends for i in end.get("staleIgnores", [])),
        "durationMs": max(end["durationMs"] for end in ends),
        "shares": len(ends),
    })
    for key in LISTS:
        whole[key] = unique(item for end in ends for item in end.get(key, []))
    return [started] + mutants + [whole], whole


def main(module, threshold, history, *paths):
    if not paths:
        sys.exit("no shares given")
    shown, whole = merged(module, int(threshold), history, paths)
    for event in shown:
        print(json.dumps(event, separators=(",", ":")))
    print(f"{module}: {whole['mutants']} mutants in {whole['shares']} shares, score "
          f"{whole['score']} against a threshold of {threshold}: "
          f"{'passed' if whole['passed'] else 'FAILED'}", file=sys.stderr)
    sys.exit(0 if whole["passed"] else 1)


if __name__ == "__main__":
    main(*sys.argv[1:])
