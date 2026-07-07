package cc4p1.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utilidad de "framing" para el protocolo de wire usado por TODO el sistema.
 *
 * IMPORTANTE: este formato es el contrato entre los 3 lenguajes (Java, Python
 * y C++). Los 3 nodos Raft deben implementar exactamente este mismo framing
 * para poder interoperar dentro de un unico cluster de consenso.
 *
 * Formato de cada mensaje sobre el socket TCP:
 *   [4 bytes]  longitud del payload en bytes, entero sin signo big-endian
 *   [N bytes]  payload en UTF-8, una linea de texto con el formato:
 *              "TIPO|campo1|campo2|...|campoN"
 *
 * Se usa longitud explicita (en vez de delimitar por '\n') porque algunos
 * campos (rutas de archivo, listas de entradas de log) son de tamaño
 * variable y podrian, en teoria, contener saltos de linea.
 */
public final class Frame {

    private Frame() {
    }

    public static void write(OutputStream out, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
    }

    public static String read(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int length = dis.readInt();
        if (length < 0 || length > 50_000_000) {
            throw new IOException("Longitud de frame invalida: " + length);
        }
        byte[] payload = new byte[length];
        dis.readFully(payload);
        return new String(payload, StandardCharsets.UTF_8);
    }
}
