

import socket

from common import frame, protocol


def descargar_pesos(host: str, port: int, destino_path: str, timeout: float = 10.0) -> int:
    """Descarga el modelo y lo guarda en destino_path. Devuelve el tamano en bytes."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        frame.write_text(sock, protocol.GET_WEIGHTS)

        meta = frame.read_text(sock)
        tipo, size_str = meta.split(protocol.FIELD_SEP)
        if tipo != "WEIGHTS_META":
            raise IOError("Respuesta inesperada del servidor de pesos: %s" % meta)
        size = int(size_str)

        received = bytearray()
        while len(received) < size:
            chunk = sock.recv(min(65536, size - len(received)))
            if not chunk:
                raise IOError("Conexion cerrada antes de recibir todos los pesos")
            received.extend(chunk)

        with open(destino_path, "wb") as f:
            f.write(received)

        return size


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 4:
        print("Uso: python pesos_cliente.py <host> <puerto> <ruta_destino>")
        sys.exit(1)

    host_arg = sys.argv[1]
    port_arg = int(sys.argv[2])
    destino_arg = sys.argv[3]
    bytes_descargados = descargar_pesos(host_arg, port_arg, destino_arg)
    print("Descargados %d bytes -> %s" % (bytes_descargados, destino_arg))
