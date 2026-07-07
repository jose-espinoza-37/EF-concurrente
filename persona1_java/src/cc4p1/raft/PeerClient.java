package cc4p1.raft;

import cc4p1.common.Frame;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Abre una conexion TCP corta hacia un peer, envia un mensaje del protocolo
 * y espera la respuesta. Si el peer no responde a tiempo (caido, red lenta,
 * particionado), se devuelve null y quien llama lo trata como un fallo
 * silencioso -- asi es como Raft tolera la caida de nodos.
 */
public class PeerClient {

    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int READ_TIMEOUT_MS = 500;

    public static String send(String host, int port, String message) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            Frame.write(socket.getOutputStream(), message);
            return Frame.read(socket.getInputStream());
        } catch (IOException e) {
            return null;
        }
    }
}
