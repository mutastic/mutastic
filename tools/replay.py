#!/usr/bin/env python3
"""Replays `mutastic next`'s order against random order, as TCAP did (Kaufman et al., ICSE 2022).

Input: the JSON Lines of `mutastic run --kill-matrix --format=jsonl`, where each caught mutant names
every covering test that catches it (`caughtByAll`). For each seed, a random half of the test
classes is held out, as if never written; the mutants the kept half reaches but doesn't catch are
the queue. A simulated developer takes the queue's head, adds a random held-out test that catches
it (a wasted unit when none does), drops every mutant that test catches, and takes the next head.
The order whose added tests catch the dominator mutants (those whose catching tests no other
mutant's are a strict subset of) sooner is the better one.

  tools/replay.py RUN.jsonl [RUN.jsonl ...] [--seeds 30] [--json]

Orders: `today`, mutastic's own (a condition or removal first, then the line more kept tests
reach, then source order); `operator`, today's without its covering-test tie-break, which is a
hypothesis; and `random`. It measures test completeness, not importance. Nothing
here adds a ranking signal: a signal goes into the order only once this shows it beats the order
without it.
"""

import argparse
import json
import random
import statistics
import sys

# The operators `Finding.prior` ranks first: a condition or a removal.
FIRST = {
    "mutastic/boundary",
    "mutastic/comparison",
    "mutastic/other-comparison",
    "mutastic/remove-conditional",
    "mutastic/logical-connector",
    "mutastic/return-value",
    "mutastic/remove-void-call",
    "mutastic/remove-discarded-call",
    "mutastic/remove-assignment",
    "mutastic/remove-field-write",
}


def read(path):
    """The mutants of one run that a test reaches, in report order, with every catching test.
    A timeout is left out: which tests would catch it isn't known."""
    latest = {}
    with open(path) as lines:
        for line in lines:
            event = json.loads(line)
            if event.get("reason") not in ("mutant-finished", "mutant-revised"):
                continue
            if event["status"] not in ("Killed", "Survived"):
                continue
            killers = event.get("caughtByAll")
            if event["status"] == "Killed" and killers is None:
                sys.exit(f"{path}: {event['id']} has no caughtByAll: run with --kill-matrix")
            location = event["location"]
            latest[event["id"]] = {
                "id": event["id"],
                "operator": event["operator"],
                "line": (location["class"], location["method"], location["line"]),
                "covered": frozenset(event["coveredBy"]),
                "killers": frozenset(killers or ()),
            }
    return list(latest.values())


def test_class(test):
    return test.split("#", 1)[0]


def dominators(mutants):
    """The distinct catching sets that no other catching set is strictly inside."""
    sets = {m["killers"] for m in mutants if m["killers"]}
    return [s for s in sets if not any(other < s for other in sets)]


def findings(queue, kept):
    """The queue as `next` has it: one item a line, each with what the order goes by."""
    by_line = {}
    for mutant in queue:
        by_line.setdefault(mutant["line"], []).append(mutant)
    items = []
    for position, mutants in enumerate(by_line.values()):
        lead = next((m for m in mutants if m["operator"] == "mutastic/boundary"), mutants[0])
        items.append(
            {
                "position": position,
                "mutants": mutants,
                "prior": 2 if any(m["operator"] in FIRST for m in mutants) else 1,
                "covering": len(lead["covered"] & kept),
            }
        )
    return items


def ordered(items, how, rng):
    if how == "random":
        shuffled = list(items)
        rng.shuffle(shuffled)
        return shuffled
    if how == "operator":
        return sorted(items, key=lambda item: (-item["prior"], item["position"]))
    return sorted(items, key=lambda item: (-item["prior"], -item["covering"], item["position"]))


def replay(mutants, how, seed):
    """One simulated developer: the dominator score after each test added, and the heads no
    held-out test catches. None when the split leaves nothing to replay."""
    tests = sorted({t for m in mutants for t in m["killers"] | m["covered"]})
    classes = sorted({test_class(t) for t in tests})
    random.Random(seed).shuffle(classes)
    kept_classes = set(classes[: len(classes) // 2])
    kept = {t for t in tests if test_class(t) in kept_classes}
    queue = [m for m in mutants if m["covered"] & kept and not m["killers"] & kept]
    goals = dominators(queue)
    if not goals:
        return None
    rng = random.Random(seed * 7919 + (1 if how == "random" else 2))
    alive = {m["id"] for m in queue}
    items = findings(queue, kept)
    added, curve, effort, wasted = set(), [], [], 0
    for item in ordered(items, how, rng):
        left = [m for m in item["mutants"] if m["id"] in alive]
        if not left:
            continue
        candidates = sorted(set().union(*(m["killers"] for m in left)))
        if not candidates:
            wasted += 1
            alive.difference_update(m["id"] for m in left)
            effort.append(curve[-1] if curve else 0.0)
            continue
        test = rng.choice(candidates)
        added.add(test)
        alive.difference_update(m["id"] for m in queue if test in m["killers"])
        curve.append(sum(1 for goal in goals if goal & added) / len(goals))
        effort.append(curve[-1])
    return {
        "curve": curve,
        "effort": effort,
        "items": len(items),
        "wasted": wasted,
        "queue": len(queue),
        "goals": len(goals),
    }


def area(result):
    """The mean dominator score over the first N tests, N the dominator count, the last score
    carried on: 1 would be every dominator caught by the first test."""
    n = result["goals"]
    curve = result["curve"][:n]
    curve += [curve[-1] if curve else 0.0] * (n - len(curve))
    return sum(curve) / n


def effort_area(result):
    """The mean dominator score over every head worked, a wasted one included: the work a
    developer does, tests that catch nothing new and heads no test catches alike."""
    n = result["items"]
    curve = result["effort"][:n]
    curve += [curve[-1] if curve else 0.0] * (n - len(curve))
    return sum(curve) / n


def reach(result, share, key="curve"):
    curve = result[key]
    return next((i + 1 for i, v in enumerate(curve) if v >= share), len(curve) + 1)


def summary(results):
    return {
        "seeds": len(results),
        "area": statistics.median(area(r) for r in results),
        "testsTo50": statistics.median(reach(r, 0.5) for r in results),
        "testsTo80": statistics.median(reach(r, 0.8) for r in results),
        "effortArea": statistics.median(effort_area(r) for r in results),
        "headsTo50": statistics.median(reach(r, 0.5, "effort") for r in results),
        "headsTo80": statistics.median(reach(r, 0.8, "effort") for r in results),
        "testsAdded": statistics.median(len(r["curve"]) for r in results),
        "wasted": statistics.median(r["wasted"] for r in results),
        "queue": statistics.median(r["queue"] for r in results),
        "dominators": statistics.median(r["goals"] for r in results),
    }


def compare(mutants, seeds):
    runs = [
        (replay(mutants, "today", s), replay(mutants, "random", s), replay(mutants, "operator", s))
        for s in range(seeds)
    ]
    runs = [run for run in runs if all(run)]
    pairs = [(t, r) for t, r, _ in runs]
    if not pairs:
        return None
    return {
        "today": summary([t for t, _ in pairs]),
        "random": summary([r for _, r in pairs]),
        "operator": summary([o for _, _, o in runs]),
        "todayAhead": sum(1 for t, r in pairs if area(t) > area(r)),
        "tied": sum(1 for t, r in pairs if area(t) == area(r)),
        "todayAheadByEffort": sum(1 for t, r in pairs if effort_area(t) > effort_area(r)),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("runs", nargs="+")
    parser.add_argument("--seeds", type=int, default=30)
    parser.add_argument("--json", action="store_true")
    arguments = parser.parse_args()
    report = {path: compare(read(path), arguments.seeds) for path in arguments.runs}
    if arguments.json:
        print(json.dumps(report, indent=2))
        return
    for path, found in report.items():
        print(path)
        if not found:
            print("  nothing to replay: no split left a mutant a held-out test catches")
            continue
        for how in ("today", "operator", "random"):
            s = found[how]
            print(
                f"  {how:6} area {s['area']:.3f}, tests to 50% {s['testsTo50']:g}, to 80%"
                f" {s['testsTo80']:g}, added {s['testsAdded']:g}, wasted {s['wasted']:g}"
                f" (queue {s['queue']:g}, dominators {s['dominators']:g})"
            )
            print(
                f"         by effort: area {s['effortArea']:.3f}, heads to 50% {s['headsTo50']:g},"
                f" to 80% {s['headsTo80']:g}"
            )
        print(
            f"  today ahead on {found['todayAhead']} of {found['today']['seeds']} seeds by tests"
            f" (tied on {found['tied']}), on {found['todayAheadByEffort']} by effort"
        )


if __name__ == "__main__":
    main()
