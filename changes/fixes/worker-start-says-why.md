- **A test worker that doesn't start says why.** It was always `internal error: … the test worker
  didn't start`, pointing at a log that CI had often deleted by then. A worker that ended before it
  was ready now gives its status and its log's last lines (`Its test worker ended with status 1
  before it was ready: Unrecognized VM option …`), and one still starting after 30 seconds says that
  a busy machine can cause it and to run again or with fewer workers. Either is a run that can't
  go on, exit 2, not mutastic's internal error.
