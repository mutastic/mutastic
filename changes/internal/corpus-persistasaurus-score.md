- **The corpus expects a score of persistasaurus, and says its score moves.** Its Maven 3.8.4
  wrapper runs since A2 was fixed (#218). Two of its tests wait on static latches across threads,
  so 7 of its 22 scored mutants are caught in one run and not the next, with no change to the
  code or mutastic: its share has scored from 68% to 96%, so its score isn't read as a signal.
