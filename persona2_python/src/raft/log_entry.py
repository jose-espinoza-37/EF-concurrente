"""
Una entrada del log replicado: un "encuentro" (evento de deteccion de
objeto/animal/persona). El formato de codificacion DEBE coincidir con
cc4p1.raft.LogEntry (Java):

    index,term,camera,clase,timestamp,imagePath

con las entradas completas separadas entre si por "~" (ProtocolMessage.ENTRY_SEP).
"""

from dataclasses import dataclass

from common import protocol


@dataclass
class LogEntry:
    index: int
    term: int
    camera: int
    clase: str
    timestamp: str
    image_path: str

    def encode(self) -> str:
        s = protocol.ENTRY_FIELD_SEP
        return s.join([
            str(self.index), str(self.term), str(self.camera),
            self.clase, self.timestamp, self.image_path,
        ])

    @staticmethod
    def decode(raw: str) -> "LogEntry":
        # limit=5 -> el 6to campo (image_path) puede contener comas si algun
        # dia se usan rutas con comas; se deja el resto como un solo campo.
        partes = raw.split(protocol.ENTRY_FIELD_SEP, 5)
        return LogEntry(
            index=int(partes[0]),
            term=int(partes[1]),
            camera=int(partes[2]),
            clase=partes[3],
            timestamp=partes[4],
            image_path=partes[5],
        )

    def __str__(self):
        return "#%d (term %d) camara=%d clase=%s hora=%s img=%s" % (
            self.index, self.term, self.camera, self.clase, self.timestamp, self.image_path
        )
