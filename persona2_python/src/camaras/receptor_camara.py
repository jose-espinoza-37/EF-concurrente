"""
Recibe el video de las cámaras IP (celular/tablet). Cada cámara real (una
app de streaming en el telefono, o una camara IP de verdad) actua como un
pequeño SERVIDOR de sockets en su propia direccion IP; este modulo actua
como CLIENTE: se conecta a cada camara y queda leyendo frames en un bucle,
uno por hilo, para no bloquear la recepcion de una camara mientras se
procesa otra.

Sub-protocolo de cada frame (payload dentro de un frame de frame.py):
    [4 bytes] width   (entero sin signo, big-endian)
    [4 bytes] height  (entero sin signo, big-endian)
    [width*height*3 bytes] pixeles RGB crudos, fila por fila

Si la conexion con una camara se cae, este modulo reintenta conectarse cada
pocos segundos indefinidamente (tolerancia a fallos de una camara individual,
independiente de las demas y del cluster Raft).
"""

import socket
import struct
import threading
import time
from typing import Callable

from common import frame

FrameCallback = Callable[[int, int, int, bytes], None]  # (camera_id, width, height, rgb_bytes)

RECONNECT_DELAY_SECONDS = 3


class ReceptorCamara(threading.Thread):
    """Un hilo dedicado a UNA camara: conecta, recibe frames, reconecta si se cae."""

    def __init__(self, camera_id: int, host: str, port: int, on_frame: FrameCallback):
        super().__init__(name="camara-%d" % camera_id, daemon=True)
        self.camera_id = camera_id
        self.host = host
        self.port = port
        self.on_frame = on_frame
        self._running = True

    def stop(self):
        self._running = False

    def run(self):
        while self._running:
            try:
                self._conectar_y_recibir()
            except (IOError, OSError) as e:
                print("[Camara %d] Desconectada (%s). Reintentando en %ds..."
                      % (self.camera_id, e, RECONNECT_DELAY_SECONDS))
            if self._running:
                time.sleep(RECONNECT_DELAY_SECONDS)

    def _conectar_y_recibir(self):
        with socket.create_connection((self.host, self.port), timeout=5) as sock:
            sock.settimeout(15)  # una camara "viva" debe mandar frames periodicamente
            print("[Camara %d] Conectada a %s:%d" % (self.camera_id, self.host, self.port))
            while self._running:
                payload = frame.read_bytes(sock)
                width, height = struct.unpack_from(">II", payload, 0)
                rgb_bytes = payload[8:]
                self.on_frame(self.camera_id, width, height, rgb_bytes)


def iniciar_receptores(camaras_config, on_frame: FrameCallback):
    """
    camaras_config: lista de dicts {"id": int, "host": str, "port": int}
    Devuelve la lista de hilos ReceptorCamara ya iniciados.
    """
    receptores = []
    for cam in camaras_config:
        r = ReceptorCamara(cam["id"], cam["host"], cam["port"], on_frame)
        r.start()
        receptores.append(r)
    return receptores
