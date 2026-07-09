

# --- Mensajes entre nodos Raft (peer-to-peer) ---
REQUEST_VOTE = "RV"
REQUEST_VOTE_REPLY = "RVR"
APPEND_ENTRIES = "AE"
APPEND_ENTRIES_REPLY = "AER"

# --- Mensajes de clientes hacia el cluster Raft ---
PROPOSE = "PROPOSE"                 # Servidor de Testeo -> lider
PROPOSE_OK = "PROPOSE_OK"
PROPOSE_REDIRECT = "PROPOSE_REDIRECT"  # "no soy el lider"
GET_LOG = "GET_LOG"                 # Cliente Vigilante -> cualquier nodo
LOG_DATA = "LOG_DATA"

# --- Mensajes del Servidor de Pesos (entrenamiento, lado Java) ---
GET_WEIGHTS = "GET_WEIGHTS"

FIELD_SEP = "|"
ENTRY_SEP = "~"
ENTRY_FIELD_SEP = ","


def split(message: str):
    """Separa un mensaje por '|' conservando campos vacios al final
    (equivalente a String.split(regex, -1) en Java)."""
    return message.split(FIELD_SEP)
