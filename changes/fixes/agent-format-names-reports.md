- **The agent format names the report files a run wrote.** It never said there were any, so an
  agent took it there was no report and ran again with `--report=html`. A `reports:` line after the
  verdict names them as the summary's Reports row does, each by a number whose path is in the
  fence, `reports: [1] · [2]` and `[1] build/mutastic/mutation.json`, and with `--report=none`
  there's no such line.
