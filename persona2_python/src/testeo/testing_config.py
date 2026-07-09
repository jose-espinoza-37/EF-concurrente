

from typing import Dict, List


class TestingConfig:
    def __init__(self, path: str):
        props = _parse_properties_file(path)

        self.weights_host = props.get("weights.server.host", "127.0.0.1")
        self.weights_port = int(props.get("weights.server.port", "6100"))
        self.modelo_cache_path = props.get("modelo.cache.path", "modelo/pesos_modelo.bin")
        self.capturas_dir = props.get("capturas.dir", "capturas")
        self.confianza_minima = float(props.get("confianza.minima", "0.5"))
        self.cluster_config_path = props.get("cluster.config.path", "config/cluster.properties")

        self.camaras: List[Dict] = []
        i = 1
        while True:
            id_key = "camara.%d.id" % i
            if id_key not in props:
                break
            self.camaras.append({
                "id": int(props[id_key]),
                "host": props.get("camara.%d.host" % i, "127.0.0.1"),
                "port": int(props.get("camara.%d.port" % i)),
            })
            i += 1


def _parse_properties_file(path: str):
    result = {}
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
