package cc4p1.raft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Lee config/cluster.properties: la lista de nodos que forman el cluster
 * Raft (uno en Java, uno en Python, uno en C++) con su host:puerto.
 *
 * Formato del archivo:
 *   cluster.nodes=java1,python1,cpp1
 *   node.java1.host=192.168.1.10
 *   node.java1.port=6001
 *   node.python1.host=192.168.1.20
 *   node.python1.port=6002
 *   node.cpp1.host=192.168.1.10
 *   node.cpp1.port=6003
 */
public class RaftConfig {

    public static class PeerInfo {
        public final String id;
        public final String host;
        public final int port;

        public PeerInfo(String id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
        }
    }

    /** Id de este nodo (ej. "java1"). */
    public final String selfId;
    /** Puerto en el que este nodo debe escuchar. */
    public final int selfPort;
    /** Resto de nodos del cluster (para enviarles RequestVote / AppendEntries). */
    public final List<PeerInfo> peers = new ArrayList<>();
    /** Todos los nodos, incluyendo este mismo (util para resolver redirects al lider). */
    public final Map<String, PeerInfo> allNodes = new LinkedHashMap<>();

    public RaftConfig(String path, String selfId) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            props.load(in);
        }
        this.selfId = selfId;

        String nodeList = props.getProperty("cluster.nodes");
        if (nodeList == null) {
            throw new IOException("cluster.properties no define 'cluster.nodes'");
        }

        Integer myPort = null;
        for (String rawId : nodeList.split(",")) {
            String id = rawId.trim();
            if (id.isEmpty()) continue;
            String host = props.getProperty("node." + id + ".host");
            String portStr = props.getProperty("node." + id + ".port");
            if (host == null || portStr == null) {
                throw new IOException("Falta host/port para el nodo '" + id + "' en cluster.properties");
            }
            int port = Integer.parseInt(portStr.trim());
            PeerInfo info = new PeerInfo(id, host, port);
            allNodes.put(id, info);
            if (id.equals(selfId)) {
                myPort = port;
            } else {
                peers.add(info);
            }
        }

        if (myPort == null) {
            throw new IOException("El nodo self '" + selfId + "' no aparece en cluster.nodes");
        }
        this.selfPort = myPort;
    }
}
