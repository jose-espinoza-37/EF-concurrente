#!/bin/bash
echo "=== Test local Raft: 3 nodos C++ + Vigilante ==="
echo ""

./build/raft_node cpp_a config/cluster-local-test.properties &
PID1=$!
sleep 1

./build/raft_node cpp_b config/cluster-local-test.properties &
PID2=$!
sleep 1

./build/raft_node cpp_c config/cluster-local-test.properties &
PID3=$!
sleep 1

./build/vigilante config/vigilante_local_test.properties &
PID4=$!

echo ""
echo "Nodos Raft: cpp_a(9001) cpp_b(9002) cpp_c(9003)"
echo "Vigilante:  http://localhost:8080"
echo ""
echo "Presiona Enter para detener todos los procesos..."
read

kill $PID1 $PID2 $PID3 $PID4 2>/dev/null
wait $PID1 $PID2 $PID3 $PID4 2>/dev/null
echo "Todos los procesos detenidos."
