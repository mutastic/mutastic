#!/usr/bin/env python3
"""Adds tonight's row to the score history and prints the history as a Markdown table.

Usage: score-row.py EVENTS_JSONL HISTORY_TSV COMMIT
"""

import datetime
import json
import pathlib
import sys

COLUMNS = ["date", "commit", "mutants", "caught", "missed", "timed out", "not covered",
           "skipped", "score", "seconds"]


def finished(events):
    for line in pathlib.Path(events).read_text().splitlines():
        event = json.loads(line)
        if event.get("reason") == "run-finished":
            return event
    sys.exit("no run-finished event: the run didn't finish")


def row(event, commit):
    return [datetime.date.today().isoformat(), commit[:7], event["mutants"], event["Killed"],
            event["Survived"], event["Timeout"], event["NoCoverage"], event["Ignored"],
            event["score"], round(event["durationMs"] / 1000)]


def main(events, history, commit):
    path = pathlib.Path(history)
    if not path.exists():
        path.write_text("\t".join(COLUMNS) + "\n")
    with path.open("a") as out:
        out.write("\t".join(str(value) for value in row(finished(events), commit)) + "\n")
    lines = [line.split("\t") for line in path.read_text().splitlines()]
    print("| " + " | ".join(lines[0]) + " |")
    print("|" + " --- |" * len(lines[0]))
    for cells in reversed(lines[1:]):
        print("| " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main(*sys.argv[1:])
