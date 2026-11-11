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

## What mutastic runs

mutastic runs your build and your tests, as your build does: treat running it on a project as you
would treating running that project's tests. It runs no network services. Its build plugins run the
`mutastic` command from their own released dependencies on Maven Central.
