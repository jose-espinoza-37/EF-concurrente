package cc4p1.raft;

import cc4p1.common.Frame;
import cc4p1.common.ProtocolMessage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Acepta conexiones TCP entrantes de:
 *   - otros nodos Raft (RV / AE)          -> peer-to-peer
 *   - el Servidor de Testeo (PROPOSE)     -> cliente que escribe
 *   - el Cliente Vigilante (GET_LOG)      -> cliente que lee
 *
 * Usa un hilo por conexion (a traves de un ExecutorService) para que una
 * conexion lenta no bloquee a las demas, y para poder atender en paralelo
 * a las 3 camaras/servidores de testeo si el proyecto escala a mas nodos.
 */
public class RaftServer implements Runnable {

    private final int port;
    private final RaftNode node;
    private final ExecutorService connectionPool = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public RaftServer(int port, RaftNode node) {
        this.port = port;
        this.node = node;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[RaftServer] Escuchando en el puerto " + port);
            while (running) {
                Socket client = serverSocket.accept();
                connectionPool.submit(() -> handleConnection(client));
            }
        } catch (IOException e) {
            System.err.println("[RaftServer] Error en el socket de escucha: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        connectionPool.shutdownNow();
    }

    private void handleConnection(Socket client) {
        try (Socket socket = client) {
            String request = Frame.read(socket.getInputStream());
            String response = dispatch(request);
            Frame.write(socket.getOutputStream(), response);
        } catch (IOException e) {
            // conexion cerrada abruptamente por el otro lado: se ignora
        }
    }

    private String dispatch(String message) {
        String[] f = ProtocolMessage.split(message);
        String type = f[0];

        switch (type) {
            case ProtocolMessage.REQUEST_VOTE: {
                long term = Long.parseLong(f[1]);
                String candidateId = f[2];
                long lastLogIndex = Long.parseLong(f[3]);
                long lastLogTerm = Long.parseLong(f[4]);
                return node.handleRequestVote(term, candidateId, lastLogIndex, lastLogTerm);
            }
            case ProtocolMessage.APPEND_ENTRIES: {
                long term = Long.parseLong(f[1]);
                String leaderId = f[2];
                long prevLogIndex = Long.parseLong(f[3]);
                long prevLogTerm = Long.parseLong(f[4]);
                long leaderCommit = Long.parseLong(f[5]);
                String entries = f.length > 6 ? f[6] : "";
                return node.handleAppendEntries(term, leaderId, prevLogIndex, prevLogTerm, leaderCommit, entries);
            }
            case ProtocolMessage.PROPOSE: {
                int camera = Integer.parseInt(f[1]);
                String clase = f[2];
                String timestamp = f[3];
                String imagePath = f[4];
                return node.handlePropose(camera, clase, timestamp, imagePath);
            }
            case ProtocolMessage.GET_LOG:
                return node.handleGetLog();
            default:
                return "ERROR|tipo_desconocido";
        }
    }
}
