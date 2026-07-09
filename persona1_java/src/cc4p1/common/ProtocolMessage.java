package cc4p1.common;


public final class ProtocolMessage {

    private ProtocolMessage() {
    }

    // --- Mensajes entre nodos Raft (peer-to-peer) ---
    public static final String REQUEST_VOTE = "RV";
    public static final String REQUEST_VOTE_REPLY = "RVR";
    public static final String APPEND_ENTRIES = "AE";
    public static final String APPEND_ENTRIES_REPLY = "AER";

    // --- Mensajes de clientes hacia el cluster Raft ---
    public static final String PROPOSE = "PROPOSE";               // Servidor de Testeo -> lider
    public static final String PROPOSE_OK = "PROPOSE_OK";
    public static final String PROPOSE_REDIRECT = "PROPOSE_REDIRECT"; // "no soy el lider"
    public static final String GET_LOG = "GET_LOG";                // Cliente Vigilante -> cualquier nodo
    public static final String LOG_DATA = "LOG_DATA";

    // --- Mensajes del Servidor de Pesos (entrenamiento) ---
    public static final String GET_WEIGHTS = "GET_WEIGHTS";

    public static final String FIELD_SEP = "\\|";
    public static final String FIELD_SEP_LITERAL = "|";
    public static final String ENTRY_SEP = "~";
    public static final String ENTRY_FIELD_SEP = ",";

    public static String[] split(String message) {
        // limit = -1 para conservar campos vacios al final (ej. lista de entries vacia)
        return message.split(FIELD_SEP, -1);
    }
}
