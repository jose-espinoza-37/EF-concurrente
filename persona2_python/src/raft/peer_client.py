"""
Abre una conexion TCP corta hacia un peer, envia un mensaje del protocolo y
espera la respuesta. Si el peer no responde a tiempo (caido, red lenta,
particionado), se devuelve None -- exactamente igual que PeerClient.java --
y quien llama lo trata como un fallo silencioso, que es como Raft tolera
fallos sin bloquearse.
"""

import socket
from typing import Optional

from common import frame

CONNECT_TIMEOUT_SECONDS = 0.3
READ_TIMEOUT_SECONDS = 0.5


def send(host: str, port: int, message: str) -> Optional[str]:
    try:
        with socket.create_connection((host, port), timeout=CONNECT_TIMEOUT_SECONDS) as sock:
            sock.settimeout(READ_TIMEOUT_SECONDS)
            frame.write_text(sock, message)
            return frame.read_text(sock)
    except (IOError, OSError):
        return None
