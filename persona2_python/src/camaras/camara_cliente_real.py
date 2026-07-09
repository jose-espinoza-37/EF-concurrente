

import argparse
import socket
import sys
import time

import cv2

from common import frame

ANCHO_DEFECTO = 32   # debe coincidir con image.width del modelo entrenado
ALTO_DEFECTO = 32    # debe coincidir con image.height del modelo entrenado


def parse_source(raw: str):
    """Permite pasar 0/1/2 (indice de webcam) o una ruta/URL de stream."""
    try:
        return int(raw)
    except ValueError:
        return raw


def capturar_y_redimensionar(cap: cv2.VideoCapture, width: int, height: int) -> bytes:
    """Captura un frame real y lo redimensiona/convierte a RGB crudo plano,
    en el mismo formato que espera el modelo (y que generaba el simulador)."""
    ok, frame_bgr = cap.read()
    if not ok:
        return None
    resized_bgr = cv2.resize(frame_bgr, (width, height), interpolation=cv2.INTER_LINEAR)
    resized_rgb = cv2.cvtColor(resized_bgr, cv2.COLOR_BGR2RGB)
    return resized_rgb.tobytes()  # ya queda fila por fila, R,G,B por pixel


def atender_cliente(conn: socket.socket, cap: cv2.VideoCapture, width: int, height: int,
                     intervalo: float, camera_id: int):
    contador = 0
    try:
        while True:
            rgb_bytes = capturar_y_redimensionar(cap, width, height)
            if rgb_bytes is None:
                print("[CamaraReal %d] no se pudo leer frame de la fuente, reintentando..." % camera_id)
                time.sleep(0.5)
                continue

            contador += 1
            payload = width.to_bytes(4, "big") + height.to_bytes(4, "big") + rgb_bytes
            frame.write_bytes(conn, payload)
            print("[CamaraReal %d] frame #%d enviado (%dx%d)" % (camera_id, contador, width, height))
            time.sleep(intervalo)
    except (IOError, OSError):
        print("[CamaraReal %d] cliente desconectado" % camera_id)
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description="Cliente de camara REAL (webcam o stream de celular)")
    parser.add_argument("--id", type=int, required=True, help="Numero de camara (1, 2, 3, ...)")
    parser.add_argument("--port", type=int, required=True, help="Puerto donde esta camara escucha")
    parser.add_argument("--source", required=True, help="0 (webcam), URL de stream, o ruta de video")
    parser.add_argument("--width", type=int, default=ANCHO_DEFECTO)
    parser.add_argument("--height", type=int, default=ALTO_DEFECTO)
    parser.add_argument("--intervalo", type=float, default=1.0, help="Segundos entre frame y frame")
    args = parser.parse_args()

    source = parse_source(args.source)
    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        print("ERROR: no se pudo abrir la fuente de video: %s" % args.source)
        sys.exit(1)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", args.port))
        server.listen(1)
        print("[CamaraReal %d] escuchando en el puerto %d, fuente=%s (%dx%d, cada %.1fs)"
              % (args.id, args.port, args.source, args.width, args.height, args.intervalo))
        try:
            while True:
                conn, addr = server.accept()
                print("[CamaraReal %d] conexion entrante de %s" % (args.id, addr))
                atender_cliente(conn, cap, args.width, args.height, args.intervalo, args.id)
        finally:
            cap.release()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)