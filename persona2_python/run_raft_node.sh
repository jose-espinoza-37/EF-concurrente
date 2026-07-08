#!/bin/bash
# Levanta el Nodo Raft #2 (Python), usando la config real del cluster.
# Uso: ./run_raft_node.sh [selfId] [rutaClusterProperties]
set -e
cd "$(dirname "$0")"
NODE_ID="${1:-python1}"
CFG="${2:-config/cluster.properties}"
PYTHONPATH=src python3 src/raft/raft_node_main.py "$NODE_ID" "$CFG"
