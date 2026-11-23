- **An `output:` rule names your own output calls.** mutastic knows the common loggers, not the
  wrapper your team logs through, an audit log, or a metric no test reads: each of their calls was
  a mutant only a test of the log text would catch. A rule in `mutastic.yaml`, `- output:
  com.acme.AuditLog#*` with the `reason:` it can't do without, has them skipped as a logger's
  calls are, under `mutastic:output`: a removed call as `arid: only calls AuditLog#info (<the
  reason>)`, a string or a value that only goes into one as `arid: only feeds AuditLog#info (…)`,
  and a check around nothing but those calls. A `mutate:` rule brings them back, and
  `MUTASTIC_ARID=audit` runs them. Nothing is output until a rule says so. In the Rust edition the
  rule names a function or a macro by its path. See
  https://docs.mutastic.io/guides/configuration/#your-own-output-calls.
