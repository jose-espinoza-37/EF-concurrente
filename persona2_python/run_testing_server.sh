#!/bin/bash
# Levanta el Servidor de Testeo de Objetos (camaras + inferencia + Raft client).
set -e
cd "$(dirname "$0")"
CFG="${1:-config/testing.properties}"
PYTHONPATH=src python3 src/testeo/main.py "$CFG"
