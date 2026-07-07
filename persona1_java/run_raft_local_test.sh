#!/bin/bash
# Prueba de humo LOCAL: levanta 3 nodos Raft (todos en Java, todos en
# localhost) para validar eleccion de lider y replicacion ANTES de integrar
# los nodos reales de Python y C++. Se detienen con Ctrl+C.
set -e
cd "$(dirname "$0")"

trap 'kill $(jobs -p) 2>/dev/null' EXIT

java -cp out cc4p1.raft.RaftNodeMain nodoA config/cluster-local-test.properties &
java -cp out cc4p1.raft.RaftNodeMain nodoB config/cluster-local-test.properties &
java -cp out cc4p1.raft.RaftNodeMain nodoC config/cluster-local-test.properties &

echo "3 nodos locales corriendo en los puertos 7001, 7002, 7003. Ctrl+C para detener."
wait
