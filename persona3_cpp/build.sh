#!/bin/bash
echo "=== Compilando Persona 3 - C++ ==="

rm -rf build
mkdir -p build

echo "  Compilando raft_node..."
g++ -std=c++17 -pthread -O2 \
    src/common/frame.cpp \
    src/raft/state_machine.cpp \
    src/raft/raft_config.cpp \
    src/raft/peer_client.cpp \
    src/raft/raft_server.cpp \
    src/raft/raft_node.cpp \
    src/raft/main.cpp \
    -o build/raft_node

if [ $? -ne 0 ]; then
    echo "  ERROR compilando raft_node"
    exit 1
fi
echo "  -> build/raft_node OK"

echo "  Compilando vigilante..."
g++ -std=c++17 -pthread -O2 \
    src/common/frame.cpp \
    src/raft/raft_config.cpp \
    src/raft/peer_client.cpp \
    src/raft/state_machine.cpp \
    src/vigilante/vigilante_config.cpp \
    src/vigilante/raft_log_client.cpp \
    src/vigilante/http_server.cpp \
    src/vigilante/main.cpp \
    -o build/vigilante

if [ $? -ne 0 ]; then
    echo "  ERROR compilando vigilante"
    exit 1
fi
echo "  -> build/vigilante OK"

echo "=== Compilacion exitosa ==="
