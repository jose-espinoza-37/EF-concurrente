

import threading
from typing import List

from raft.log_entry import LogEntry


class StateMachine:
    def __init__(self):
        self._encounters: List[LogEntry] = []
        self._lock = threading.Lock()

    def apply(self, entry: LogEntry) -> None:
        with self._lock:
            self._encounters.append(entry)

    def snapshot(self) -> List[LogEntry]:
        with self._lock:
            return list(self._encounters)

    def size(self) -> int:
        with self._lock:
            return len(self._encounters)
