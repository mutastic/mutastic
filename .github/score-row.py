#!/usr/bin/env python3
"""Adds tonight's row for one module to the score history and prints the history as a Markdown
table.

Usage: score-row.py EVENTS_JSONL HISTORY_TSV COMMIT MODULE
"""

import datetime
import json
import pathlib
import sys

COLUMNS = ["date", "commit", "module", "mutants", "caught", "missed", "timed out", "not covered",
           "skipped", "score", "seconds"]


def finished(events):
    for line in pathlib.Path(events).read_text().splitlines():
        event = json.loads(line)
        if event.get("reason") == "run-finished":
            return event
    sys.exit("no run-finished event: the run didn't finish")


def row(event, commit, module):
    return [datetime.date.today().isoformat(), commit[:7], module, event["mutants"], event["killed"],
            event["survived"], event["timeout"], event["noCoverage"], event["ignored"],
            event["score"], round(event["durationMs"] / 1000)]


def with_modules(path):
    """Rows from before the module column were all the engine's, before it was split."""
    lines = path.read_text().splitlines()
    if lines and "module" not in lines[0].split("\t"):
        rows = [cells[:2] + ["mutastic-engine (before the split)"] + cells[2:]
                for cells in (line.split("\t") for line in lines[1:])]
        path.write_text("\n".join(["\t".join(COLUMNS)] + ["\t".join(r) for r in rows]) + "\n")


def main(events, history, commit, module):
    path = pathlib.Path(history)
    if not path.exists():
        path.write_text("\t".join(COLUMNS) + "\n")
    with_modules(path)
    with path.open("a") as out:
        out.write("\t".join(str(value) for value in row(finished(events), commit, module)) + "\n")
    lines = [line.split("\t") for line in path.read_text().splitlines()]
    print("| " + " | ".join(lines[0]) + " |")
    print("|" + " --- |" * len(lines[0]))
    for cells in reversed(lines[1:]):
        print("| " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main(*sys.argv[1:])
