#!/bin/bash
# Simula una camara IP (celular/tablet) mientras no se tenga hardware real.
# Uso: ./run_camera_sim.sh <id> <puerto> [intervalo_segundos]
set -e
cd "$(dirname "$0")"
ID="$1"
PORT="$2"
INTERVALO="${3:-2.0}"
PYTHONPATH=src python3 src/camaras/camara_cliente_movil.py --id "$ID" --port "$PORT" --intervalo "$INTERVALO"
