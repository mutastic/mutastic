"""The corpus's judgements, for scripts/corpus-run.sh: which entries to run, what a run's output
says, and the night's table. The shell script runs things; this reads what they left.

    corpus.py matrix ONLY < entries        check the entries, print those ONLY picks as JSON
    corpus.py judge NAME RED STATUS SECONDS EVENTS LOG AGENT AGENT_STATUS PROJECT < entry
    corpus.py summary DIR PREV NAME… < entries

The entries are corpus.yaml as JSON, one to a line (yq -o=json -I=0 '.[]'). Python 3.9 or later,
the standard library only.
"""

import json
import os
import re
import sys

# The exit statuses `timeout` gives a run it stopped: TERM, then KILL after the grace.
STOPPED = (124, 137)
JDKS = (17, 21, 25)
# The lines the agent format's head may have between the verdict and the rules.
HEAD = ("reports: ", "vs last run: ", "likely flaky: ", "flaky: ", "note: ", "stale ignores: ")
# What a night counts as good: a pass, a refusal it expects, a known bug failing as it does.
GOOD = ("pass", "known")


def read_entries():
    return [json.loads(line) for line in sys.stdin if line.strip()]


def problems_of(entry, where):
    """What's wrong with one entry, as sentences."""
    problems = []
    for key in ("git", "dir"):
        if not isinstance(entry.get(key), str) or not entry[key]:
            problems.append(f"{where}: no {key}")
    if not re.fullmatch(r"[0-9a-f]{40}", str(entry.get("commit", ""))):
        problems.append(f"{where}: commit is a whole hash, 40 hex digits")
    shard = entry.get("shard")
    if not isinstance(shard, int) or not 1 <= shard <= 64:
        problems.append(f"{where}: shard is N for --shard=1/N, 1 to 64")
    if entry.get("jdk") not in JDKS:
        problems.append(f"{where}: jdk is one of " + ", ".join(map(str, JDKS)))
    module = entry.get("module")
    if module is not None and not (isinstance(module, str) and module.startswith(":")):
        problems.append(f"{where}: module is a module as mutastic run takes it, :name")
    args = entry.get("args", [])
    if not isinstance(args, list) or not all(isinstance(arg, str) and arg.startswith("--") for arg in args):
        problems.append(f"{where}: args is a list of options")
    expect = str(entry.get("expect", ""))
    if not re.fullmatch(r"score|refused:[a-z][a-z0-9-]*|known:[A-Z][0-9]+[a-z]?", expect):
        problems.append(f"{where}: expect is score, refused:<code> or known:<bug>")
    elif expect != "score" and not entry.get("note"):
        problems.append(f"{where}: {expect} needs a note saying why")
    if expect.startswith("known:") and not re.fullmatch(r"[a-z][a-z0-9-]*", str(entry.get("fails", ""))):
        problems.append(f"{where}: {expect} needs fails:, the code it fails with today")
    if not expect.startswith("known:") and "fails" in entry:
        problems.append(f"{where}: fails: is for a known:<bug> only")
    if not entry.get("shapes"):
        problems.append(f"{where}: shapes says what it covers that the others don't")
    return problems


def matrix(only):
    """Every entry checked, then those `only` picks (all of them when it's empty), as a JSON array
    of {name, jdk} for a matrix."""
    picked = only.replace(",", " ").split()
    entries = read_entries()
    problems = []
    names = []
    for index, entry in enumerate(entries, 1):
        name = entry.get("name")
        where = f"entry {index} ({name or 'no name'})"
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
            problems.append(f"{where}: name is one word of letters, digits, _, . or -")
        elif name in names:
            problems.append(f"{where}: another entry is called {name}")
        names.append(name)
        problems.extend(problems_of(entry, where))
    unknown = [name for name in picked if name not in names]
    if unknown:
        problems.append("no entry called " + ", ".join(unknown))
    if problems:
        print("\n".join("corpus: " + problem for problem in problems), file=sys.stderr)
        return 2
    chosen = [{"name": e["name"], "jdk": e["jdk"]} for e in entries if not picked or e["name"] in picked]
    print(json.dumps(chosen))
    return 0


def first_line(event):
    message = event.get("message") or ""
    return message.splitlines()[0] if message else ""


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as file:
            return file.read()
    except OSError:
        return ""


def last_event(text):
    """The run's last run-finished or run-failed."""
    last = None
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get("reason") in ("run-finished", "run-failed"):
            last = event
    return last


def fence_shape(lines):
    """What's wrong with what follows the rules: the fence, alone, its token matching."""
    opening = re.match(r"<<mutastic-data (?P<token>[0-9a-f]{8})>> ", lines[0]) if lines else None
    if not opening:
        return "after the rules, a line outside the fence: " + (lines[0][:80] if lines else "nothing")
    if lines[-1] != f"<<end mutastic-data {opening.group('token')}>>":
        return "the fence doesn't close with its token on the last line"
    markers = [line for line in lines if re.match(r"<<(end )?mutastic-data ", line)]
    if len(markers) != 2:
        return f"{len(markers)} fence markers, not one fence"
    return "ok"


def agent_shape(path, status, finished):
    """What's wrong with the agent run's head, or "ok". The verdict first; then the head's lines,
    reports: among them; then, with a mutant missed or not covered, rules:, decline:, maybe
    tasks:, and one fence with matching tokens around the rest."""
    if status in STOPPED:
        return "stopped at the time limit"
    lines = read(path).rstrip("\n").split("\n")
    if not re.match(r"mutastic: (PASS|FAIL)\b", lines[0]):
        return "line 1 isn't the verdict: " + lines[0][:80]
    rules = next((at for at, line in enumerate(lines) if line.startswith("rules: ")), len(lines))
    for line in lines[1:rules]:
        if not line.startswith(HEAD):
            return "before rules:, a line that isn't the head's: " + line[:80]
    if not any(line.startswith("reports: ") for line in lines[1:rules]):
        return "no reports: line"
    gaps = (finished.get("survived") or 0) + (finished.get("noCoverage") or 0)
    if rules == len(lines):
        return "no rules: line, with mutants missed" if gaps else "ok"
    if not gaps:
        return "a rules: line, with no mutant missed"
    if rules + 1 >= len(lines) or not lines[rules + 1].startswith("decline: "):
        return "decline: doesn't follow rules:"
    rest = lines[rules + 2 :]
    if rest and rest[0].startswith("tasks: "):
        rest = rest[1:]
    return fence_shape(rest)


def reports_missing(finished, project):
    """Whether the run named no report, or one it named isn't there."""
    reports = finished.get("reports") or []
    if not reports:
        return "named no report"
    for report in reports:
        if not os.path.isfile(os.path.join(project, report)):
            return f"named {report}, which isn't there"
    return ""


def what_it_did(status, last, text, project):
    """What the run came to: ("score", "") when it ended as a score should, else a code and why."""
    if status in STOPPED:
        return "time-limit", f"stopped at the time limit (exit {status})"
    if last is None:
        if status == 4:
            return "internal", "an internal error, with no last event"
        return "no-last-event", f"ended with no last event (exit {status}): it crashed or was stopped"
    if last["reason"] == "run-failed":
        return last.get("code") or "other", first_line(last)
    if status == 4:
        return "internal", "an internal error after it finished"
    if status == 3 or "without any mutant" in text:
        return "tests-fail-without-any-mutant", "the tests fail without any mutant"
    if not last.get("mutants"):
        return "no-mutants", "finished with 0 mutants"
    if last.get("score") is None:
        return "no-score", "run-finished has no score"
    if status != 0:
        return f"exit-{status}", f"finished, but exited {status} at threshold 0"
    missing = reports_missing(last, project)
    if missing:
        return "no-report", "finished, but " + missing
    return "score", ""


def judge(name, red, status, seconds, events, log, agent, agent_status, project):
    """The result line, and whether it failed. The entry is on stdin, as JSON."""
    entry = json.loads(sys.stdin.read())
    expect = entry["expect"]
    result = {
        "name": name,
        "expect": expect,
        "outcome": "fail",
        "exit": None,
        "mutants": None,
        "score": None,
        "seconds": None,
        "got": None,
        "reason": "",
        "agent": None,
    }

    def done(outcome, reason):
        result["outcome"] = outcome
        result["reason"] = reason
        print(json.dumps(result))
        return 1 if outcome in ("fail", "fixed") else 0

    if red:
        return done("project-red", red)
    status = int(status)
    result["exit"] = status
    result["seconds"] = int(seconds)
    text = read(events) + read(log)
    last = last_event(read(events))
    if last and last["reason"] == "run-finished":
        result["mutants"] = last.get("mutants")
        result["score"] = last.get("score")
    got, why = what_it_did(status, last, text, project)
    if got == "score":
        result["agent"] = agent_shape(agent, int(agent_status), last)
        if result["agent"] != "ok":
            got, why = "agent-head", "agent head: " + result["agent"]
    result["got"] = got

    if expect == "score":
        return done("pass", "") if got == "score" else done("fail", f"{got}: {why}")
    if expect.startswith("refused:"):
        wanted = expect.split(":", 1)[1]
        if got == wanted:
            return done("pass", why)
        if got == "score":
            return done("fixed", f"expected {expect}, but it scores now: update expect")
        return done("fail", f"expected {expect}, got {got}: {why}")
    bug, fails = expect.split(":", 1)[1], entry["fails"]
    if got == "score":
        return done("fixed", f"{bug} now passes, update expect to score")
    if got == fails:
        return done("known", f"{bug}: {why}")
    return done("fail", f"{bug} failed with {fails}, but now {got}: {why}")


def cell(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def shown(result, regressed):
    outcome = result["outcome"]
    if outcome == "pass" and str(result["expect"]).startswith("refused:"):
        return "✓ refused, as expected"
    if outcome == "pass":
        return "✓ pass"
    if outcome == "known":
        return "○ known: " + cell(result["reason"])
    if outcome == "fixed":
        return f"**↑ {cell(result['reason'])}**"
    if outcome == "project-red":
        return "– project red: " + cell(result["reason"])
    if regressed:
        return f"**✗ fails now: {cell(result['reason'])}**"
    return "✗ " + cell(result["reason"])


def results_in(results_dir, names, corpus):
    """Each name's result line from DIR, or a failure for a job that stopped before it wrote one."""
    results = []
    for name in names:
        lines = read(os.path.join(results_dir, name + ".result.json")).strip().splitlines()
        try:
            results.append(json.loads(lines[-1]))
        except (ValueError, IndexError):
            expect = corpus.get(name, {}).get("expect") or ""
            reason = "no result: its job stopped before it wrote one"
            results.append({"name": name, "expect": expect, "outcome": "fail", "reason": reason})
    return results


def summary(results_dir, previous, names):
    """DIR's results as one file, DIR/corpus-results.jsonl, and a Markdown table for the job
    summary, against PREV, the last good night's file. A project that was good then and fails now
    is what the night is for: it's named, red, and the exit is 1. So is one whose known bug is
    fixed, until its expect says score."""
    corpus = {entry["name"]: entry for entry in read_entries()}
    results = results_in(results_dir, names, corpus)
    with open(os.path.join(results_dir, "corpus-results.jsonl"), "w", encoding="utf-8") as merged:
        for result in results:
            merged.write(json.dumps(result) + "\n")
    before = {}
    for line in read(previous).splitlines():
        if line.strip():
            earlier = json.loads(line)
            before[earlier["name"]] = earlier["outcome"]

    regressed = {r["name"] for r in results if r["outcome"] == "fail" and before.get(r["name"]) in GOOD}
    fixed = [r for r in results if r["outcome"] == "fixed"]
    counts = {}
    for result in results:
        counts[result["outcome"]] = counts.get(result["outcome"], 0) + 1
    print(f"## Corpus: {len(results)} projects")
    print()
    tally = ", ".join(f"{count} {outcome}" for outcome, count in sorted(counts.items()))
    print(tally + ("" if before else "; no earlier results to compare with"))
    print()
    for result in results:
        name, reason = result["name"], result["reason"]
        if name in regressed:
            print(f"- **✗ {name} was good last time and fails now:** {cell(reason)}")
            print(f"::error title=Corpus: {name} fails now::{name} was good last time: {reason}", file=sys.stderr)
    for result in fixed:
        name, reason = result["name"], result["reason"]
        print(f"- **↑ {name}:** {cell(reason)} in corpus/corpus.yaml")
        print(f"::error title=Corpus: {name} now passes::{reason} in corpus/corpus.yaml", file=sys.stderr)
    if regressed or fixed:
        print()
    print("| Project | Expect | Result | Mutants | Score | Took | Last time |")
    print("| --- | --- | --- | ---: | ---: | ---: | --- |")
    for result in results:
        name = result["name"]
        score = "" if result.get("score") is None else f"{result['score']}%"
        mutants = "" if result.get("mutants") is None else result["mutants"]
        took = "" if result.get("seconds") is None else f"{result['seconds']}s"
        print(
            f"| {name} | {result['expect']} | {shown(result, name in regressed)} | {mutants} | {score} "
            f"| {took} | {before.get(name, '—')} |"
        )
    return 1 if regressed or fixed else 0


def main(args):
    if args[:1] == ["matrix"] and len(args) == 2:
        return matrix(args[1])
    if args[:1] == ["judge"] and len(args) == 10:
        return judge(*args[1:])
    if args[:1] == ["summary"] and len(args) >= 3:
        return summary(args[1], args[2], args[3:])
    print("corpus.py matrix ONLY | judge … | summary DIR PREV NAME…", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
