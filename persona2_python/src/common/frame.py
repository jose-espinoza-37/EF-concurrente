

import socket
import struct

MAX_FRAME_SIZE = 50_000_000


def write_bytes(sock: socket.socket, payload: bytes) -> None:
    """Escribe un frame binario crudo: 4 bytes de longitud (big-endian) + payload."""
    header = struct.pack(">I", len(payload))
    sock.sendall(header + payload)


def read_bytes(sock: socket.socket) -> bytes:
    """Lee un frame binario crudo. Bloquea hasta completar el payload
    (equivalente a DataInputStream.readFully en Java)."""
    header = _recv_exact(sock, 4)
    (length,) = struct.unpack(">I", header)
    if length < 0 or length > MAX_FRAME_SIZE:
        raise IOError("Longitud de frame invalida: %d" % length)
    return _recv_exact(sock, length)


def write_text(sock: socket.socket, message: str) -> None:
    """Escribe un mensaje de texto (protocolo de control) como frame UTF-8."""
    write_bytes(sock, message.encode("utf-8"))


def read_text(sock: socket.socket) -> str:
    """Lee un mensaje de texto (protocolo de control)."""
    return read_bytes(sock).decode("utf-8")


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    """recv() puede devolver menos bytes de los pedidos; este helper insiste
    hasta completar exactamente n bytes o lanza IOError si el socket se cierra
    antes (mismo comportamiento que DataInputStream.readFully en Java)."""
    chunks = []
    remaining = n
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise IOError("Socket cerrado antes de completar el frame")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)
