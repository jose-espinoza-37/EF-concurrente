"""
El Servidor de Testeo NO es un nodo Raft: es un CLIENTE del cluster Raft.
Este modulo implementa el mismo patron "probar un nodo, seguir el redirect
si no es el lider" que ya se probo funcionando entre Java y Python durante
el desarrollo (ver README para el detalle de esa prueba).
"""

import time
from typing import Optional

from common import protocol
from raft import peer_client
from raft.raft_config import RaftClusterView, PeerInfo

MAX_INTENTOS = 6
ESPERA_SIN_LIDER_S = 0.5


def proponer_evento(cluster_config: RaftClusterView, camera: int, clase: str,
                     timestamp: str, image_path: str):
    """Envia PROPOSE al cluster, seguendo redirects hasta encontrar al lider.
    Devuelve el indice de log asignado, o None si no se pudo tras varios intentos."""
    candidatos = list(cluster_config.all_nodes.values())
    if not candidatos:
        return None

    nodo_actual = candidatos[0]
    visitados = set()

    for _intento in range(MAX_INTENTOS):
        msg = "%s|%d|%s|%s|%s" % (protocol.PROPOSE, camera, clase, timestamp, image_path)
        respuesta = peer_client.send(nodo_actual.host, nodo_actual.port, msg)

        if respuesta is None:
            visitados.add((nodo_actual.host, nodo_actual.port))
            siguiente = _siguiente_no_visitado(candidatos, visitados)
            if siguiente is None:
                return None
            nodo_actual = siguiente
            continue

        partes = protocol.split(respuesta)
        if partes[0] == protocol.PROPOSE_OK:
            return int(partes[1])

        if partes[0] == protocol.PROPOSE_REDIRECT:
            host, port_str = partes[1], partes[2]
            if host == "NONE":
                time.sleep(ESPERA_SIN_LIDER_S)  # cluster sin lider aun (eleccion en curso)
                continue
            nodo_actual = PeerInfo("lider", host, int(port_str))
            continue

        return None  # respuesta inesperada

    return None


def _siguiente_no_visitado(candidatos, visitados):
    for c in candidatos:
        if (c.host, c.port) not in visitados:
            return c
    return None
