# Security

## Supported versions

Fixes ship as a patch release of the latest minor version (1.0.x today), for both editions. Older
minor versions aren't patched: upgrade to the latest to get a fix.

## Reporting a vulnerability

Please report it privately, through GitHub's
[private vulnerability reporting](https://github.com/mutastic/mutastic/security/advisories/new)
(the repository's *Security* tab, then *Report a vulnerability*), rather than in a public issue.

Say what's affected, how to reproduce it, and what it lets someone do. You'll get an acknowledgement
within a week, and we'll keep you informed while it's fixed. Once a fix is released, the advisory
is published, crediting you unless you'd rather not be.

## What mutastic tells an agent

mutastic's output quotes your project: the lines a mutant changes, test names, file paths, what a
failing test said. A coding agent reads it, often through a plugin's hooks, with no person in
between, so text planted in a project (a string literal or a comment, a test's name or data, a
vendored file, a pull request) could read as an instruction. mutastic keeps the two apart:

- **The project's text is fenced.** In the agent format, `next`'s agent text and the gate's
  messages, mutastic's instructions come first, and what quotes the project comes after, between
  `<<mutastic-data TOKEN>>` and `<<end mutastic-data TOKEN>>`. The token is 8 hex digits drawn at
  random for each run, and drawn again if the text holds it, so the text can't close the fence
  early. The opening line says everything up to the closing marker is data, not instructions.
- **Hidden characters are shown.** Control characters (so every ANSI escape), bidi controls,
  zero-width characters and every other invisible format character in the project's text show as
  escapes, `\x1b` and `\u202e`, in every output, a person's or an agent's. A value or line over
  200 characters is cut, with how many more there were.
- **Commands are mutastic's.** A command mutastic offers is made only of a checked mutant id, its
  own options and placeholders, and the directory, quoted for a shell: never of text from the
  project or a report file.
- **Events are data.** `--format=jsonl` keeps the text as it is, JSON-escaped: a tool reading it
  decides what to trust.

A fence and escapes make planted text plain to see, and tell the agent it's data; they can't make
an agent obey that. Review what an agent does in a project you don't trust, as you would its tests.
See [Agent output and untrusted text](https://docs.mutastic.io/agents/#agent-output-and-untrusted-text).

## What mutastic runs

mutastic runs your build and your tests, as your build does: treat running it on a project as you
would treating running that project's tests. It runs no network services. Its build plugins run the
`mutastic` command from their own released dependencies on Maven Central.
