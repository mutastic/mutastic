- **A for-each loop over an array is told as the loop.** Its card said what javac compiles it to,
  `local4 < local3` is always true. It now says what the loop does: the for-each loop over `items`
  reads past its last element, an ArrayIndexOutOfBoundsException, or never runs its body.
