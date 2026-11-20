- **A pool's largest size beside a queue with no limit is skipped as equivalent.** A
  `ThreadPoolExecutor` only grows past its core size when its queue is full, and a
  `LinkedBlockingQueue` made with no bound never is, so changing its largest size to another no
  smaller than the core size changes nothing. A class that changes or reads a pool's sizes keeps
  them.
