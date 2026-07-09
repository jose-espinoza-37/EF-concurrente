

from typing import Dict, List, NamedTuple


class PeerInfo(NamedTuple):
    node_id: str
    host: str
    port: int


class RaftConfig:
    def __init__(self, path: str, self_id: str):
        props = _parse_properties_file(path)

        self.self_id = self_id
        node_list = props.get("cluster.nodes")
        if not node_list:
            raise ValueError("cluster.properties no define 'cluster.nodes'")

        self.peers: List[PeerInfo] = []
        self.all_nodes: Dict[str, PeerInfo] = {}

        self_port = None
        for raw_id in node_list.split(","):
            node_id = raw_id.strip()
            if not node_id:
                continue
            host = props.get("node.%s.host" % node_id)
            port_str = props.get("node.%s.port" % node_id)
            if host is None or port_str is None:
                raise ValueError("Falta host/port para el nodo '%s' en cluster.properties" % node_id)
            info = PeerInfo(node_id, host, int(port_str))
            self.all_nodes[node_id] = info
            if node_id == self_id:
                self_port = info.port
            else:
                self.peers.append(info)

        if self_port is None:
            raise ValueError("El nodo self '%s' no aparece en cluster.nodes" % self_id)
        self.self_port = self_port


class RaftClusterView:
    """
    Version ligera de RaftConfig para quien SOLO necesita conocer las
    direcciones del cluster (por ejemplo, el Servidor de Testeo, que es un
    CLIENTE de Raft, no un nodo miembro, y por lo tanto no tiene un
    self_id que deba aparecer en cluster.nodes).
    """

    def __init__(self, path: str):
        props = _parse_properties_file(path)
        node_list = props.get("cluster.nodes")
        if not node_list:
            raise ValueError("cluster.properties no define 'cluster.nodes'")

        self.all_nodes: Dict[str, PeerInfo] = {}
        for raw_id in node_list.split(","):
            node_id = raw_id.strip()
            if not node_id:
                continue
            host = props.get("node.%s.host" % node_id)
            port_str = props.get("node.%s.port" % node_id)
            if host is None or port_str is None:
                raise ValueError("Falta host/port para el nodo '%s' en cluster.properties" % node_id)
            self.all_nodes[node_id] = PeerInfo(node_id, host, int(port_str))


def _parse_properties_file(path: str) -> Dict[str, str]:
    result: Dict[str, str] = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            result[key.strip()] = value.strip()
    return result
