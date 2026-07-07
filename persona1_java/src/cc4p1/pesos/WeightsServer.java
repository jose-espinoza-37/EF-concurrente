package cc4p1.pesos;

import cc4p1.common.Frame;
import cc4p1.common.ProtocolMessage;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Expone por socket el archivo de pesos ya entrenado (ver
 * {@link cc4p1.entrenamiento.ModelPersistence} para el formato del archivo),
 * para que el Servidor de Testeo (tipicamente en Python, en otra maquina/SO)
 * lo pueda descargar sin necesidad de un filesystem compartido.
 *
 * Protocolo (sobre el mismo framing de {@link Frame}):
 *   Cliente  -> "GET_WEIGHTS"                       (un Frame de texto)
 *   Servidor -> "WEIGHTS_META|<tamano_en_bytes>"      (un Frame de texto)
 *   Servidor -> <tamano_en_bytes> bytes RAW del archivo (sin framing extra,
 *               el receptor ya sabe cuantos bytes leer por el paso anterior)
 *
 * Atiende conexiones concurrentes con un pool de hilos: varias instancias
 * del Servidor de Testeo (o reintentos) pueden descargar el modelo a la vez.
 */
public class WeightsServer implements Runnable {

    private final int port;
    private final String modelPath;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public WeightsServer(int port, String modelPath) {
        this.port = port;
        this.modelPath = modelPath;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[WeightsServer] Escuchando en el puerto " + port
                    + " (sirviendo " + modelPath + ")");
            while (running) {
                Socket client = serverSocket.accept();
                pool.submit(() -> handle(client));
            }
        } catch (IOException e) {
            System.err.println("[WeightsServer] Error: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        pool.shutdownNow();
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            String request = Frame.read(socket.getInputStream());
            if (!request.equals(ProtocolMessage.GET_WEIGHTS)) {
                Frame.write(socket.getOutputStream(), "ERROR|solicitud_desconocida");
                return;
            }

            byte[] fileBytes = Files.readAllBytes(Paths.get(modelPath));
            Frame.write(socket.getOutputStream(), "WEIGHTS_META|" + fileBytes.length);

            OutputStream out = socket.getOutputStream();
            out.write(fileBytes);
            out.flush();
            System.out.println("[WeightsServer] Pesos enviados a "
                    + socket.getRemoteSocketAddress() + " (" + fileBytes.length + " bytes)");
        } catch (IOException e) {
            System.err.println("[WeightsServer] Fallo atendiendo cliente: " + e.getMessage());
        }
    }
}
