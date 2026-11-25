- **The editions' contract says how arguments reach the build tool.** Decision 29: those after
  `--`, else `MUTASTIC_BUILD_ARGS`, else `build:`'s `args:` in `mutastic.yaml`, on each build call
  and never to the tests. Both editions' sides are pending (N81, N82).
