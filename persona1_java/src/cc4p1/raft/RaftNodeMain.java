package cc4p1.raft;

import java.util.concurrent.TimeUnit;

/**
 * Punto de entrada del Nodo Raft #1 (Java).
 *
 * Uso:
 *   java cc4p1.raft.RaftNodeMain <selfId> <rutaClusterProperties>
 *
 * Ejemplo:
 *   java cc4p1.raft.RaftNodeMain java1 config/cluster.properties
 */
public class RaftNodeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: RaftNodeMain <selfId> <rutaClusterProperties>");
            System.exit(1);
        }
        String selfId = args[0];
        String configPath = args[1];

        RaftConfig config = new RaftConfig(configPath, selfId);
        StateMachine stateMachine = new StateMachine();
        RaftNode node = new RaftNode(config, stateMachine);
        RaftServer server = new RaftServer(config.selfPort, node);

        Thread serverThread = new Thread(server, "raft-server-" + selfId);
        serverThread.setDaemon(false);
        serverThread.start();

        node.start();

        // Hilo separado que imprime el estado del nodo cada 5s (util para
        // demostrar en vivo la eleccion de lider y la replicacion del log).
        Thread statusThread = new Thread(() -> {
            while (true) {
                try {
                    TimeUnit.SECONDS.sleep(5);
                    System.out.println(node.describeStatus());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "status-" + selfId);
        statusThread.setDaemon(true);
        statusThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
