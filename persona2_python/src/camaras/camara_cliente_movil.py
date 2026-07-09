

import argparse
import random
import socket
import struct
import sys
import time

from common import frame

ANCHO_DEFECTO = 32
ALTO_DEFECTO = 32


def generar_frame(width: int, height: int, seed_counter: int) -> bytes:
    """Genera una imagen sintetica (bloques de color con ruido), simulando
    una deteccion distinta cada cierto numero de frames para poder ver
    variedad en el registro del Cliente Vigilante durante las pruebas."""
    rnd = random.Random(seed_counter)
    base_r = (seed_counter * 53) % 256
    base_g = (seed_counter * 97) % 256
    base_b = (seed_counter * 181) % 256

    pixels = bytearray(width * height * 3)
    idx = 0
    for _ in range(height):
        for _ in range(width):
            noise = rnd.randint(-20, 20)
            pixels[idx] = max(0, min(255, base_r + noise))
            pixels[idx + 1] = max(0, min(255, base_g + noise))
            pixels[idx + 2] = max(0, min(255, base_b + noise))
            idx += 3
    return bytes(pixels)


def atender_cliente(conn: socket.socket, width: int, height: int, intervalo: float, camera_id: int):
    contador = 0
    try:
        while True:
            contador += 1
            rgb = generar_frame(width, height, contador)
            payload = struct.pack(">II", width, height) + rgb
            frame.write_bytes(conn, payload)
            print("[CamaraSim %d] frame #%d enviado (%dx%d)" % (camera_id, contador, width, height))
            time.sleep(intervalo)
    except (IOError, OSError):
        print("[CamaraSim %d] cliente desconectado" % camera_id)
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description="Simulador de camara IP (celular/tablet)")
    parser.add_argument("--id", type=int, required=True, help="Numero de camara (1, 2, 3, ...)")
    parser.add_argument("--port", type=int, required=True, help="Puerto donde esta camara escucha")
    parser.add_argument("--width", type=int, default=ANCHO_DEFECTO)
    parser.add_argument("--height", type=int, default=ALTO_DEFECTO)
    parser.add_argument("--intervalo", type=float, default=2.0, help="Segundos entre frame y frame")
    args = parser.parse_args()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", args.port))
        server.listen(1)
        print("[CamaraSim %d] escuchando en el puerto %d (%dx%d, cada %.1fs)"
              % (args.id, args.port, args.width, args.height, args.intervalo))
        while True:
            conn, addr = server.accept()
            print("[CamaraSim %d] conexion entrante de %s" % (args.id, addr))
            atender_cliente(conn, args.width, args.height, args.intervalo, args.id)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
