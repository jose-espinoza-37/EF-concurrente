

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
