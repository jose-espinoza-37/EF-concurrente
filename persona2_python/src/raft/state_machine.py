"""
Maquina de estados de Raft: aplica las entradas ya comprometidas (commit) y
mantiene el registro de "encuentros" que finalmente consulta el Cliente
Vigilante.

Nota de diseño: en Java se usa un ReentrantReadWriteLock para permitir
lecturas concurrentes sin bloquearse entre si. Python no trae un
read-write-lock en su biblioteca estandar; dado el volumen de datos de este
proyecto (cientos/miles de entradas, no millones) un threading.Lock simple
es suficiente y evita introducir complejidad adicional sin beneficio real
de rendimiento medible a esta escala.
"""

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
