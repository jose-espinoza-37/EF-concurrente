#!/bin/bash
# Levanta el Nodo Raft #1 (Java), usando la configuracion real del cluster
# heterogeneo (config/cluster.properties -- ajustar las IPs antes de usar).
set -e
cd "$(dirname "$0")"
java -cp out cc4p1.raft.RaftNodeMain java1 config/cluster.properties
