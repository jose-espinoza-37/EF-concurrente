

import os
import time
import traceback
from datetime import datetime

from camaras.receptor_camara import iniciar_receptores
from common.bmp import save_bmp
from common.image_utils import resize_nearest
from modelo.model_loader import load_model
from modelo.pesos_cliente import descargar_pesos
from modelo.red_neuronal import RedNeuronal
from raft.raft_config import RaftClusterView
from testeo.raft_client_helper import proponer_evento
from testeo.testing_config import TestingConfig


class ServidorDeTesteo:
    def __init__(self, testing_config_path: str):
        self.config = TestingConfig(testing_config_path)
        self.cluster_config = RaftClusterView(self.config.cluster_config_path)
        self.red = None  # se completa en preparar_modelo()

    def preparar_modelo(self):
        os.makedirs(os.path.dirname(self.config.modelo_cache_path) or ".", exist_ok=True)
        try:
            print("[ServidorTesteo] Descargando modelo desde %s:%d ..." %
                  (self.config.weights_host, self.config.weights_port))
            tam = descargar_pesos(self.config.weights_host, self.config.weights_port,
                                   self.config.modelo_cache_path)
            print("[ServidorTesteo] Modelo descargado (%d bytes)" % tam)
        except (IOError, OSError) as e:
            if os.path.exists(self.config.modelo_cache_path):
                print("[ServidorTesteo] No se pudo descargar (%s); usando copia en cache." % e)
            else:
                raise RuntimeError(
                    "No se pudo descargar el modelo y no hay copia en cache. "
                    "Verifique que el Servidor de Pesos (Java) este corriendo."
                ) from e

        modelo = load_model(self.config.modelo_cache_path)
        print("[ServidorTesteo] Clases: %s (entrada %dx%d)" %
              (modelo.class_names, modelo.image_width, modelo.image_height))
        self.red = RedNeuronal(modelo)

    def iniciar(self):
        self.preparar_modelo()
        os.makedirs(self.config.capturas_dir, exist_ok=True)

        print("[ServidorTesteo] Iniciando receptores de %d camara(s)..." % len(self.config.camaras))
        receptores = iniciar_receptores(self.config.camaras, self._on_frame)

        print("[ServidorTesteo] Listo. Esperando frames de las camaras (Ctrl+C para salir).")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            for r in receptores:
                r.stop()

    def _on_frame(self, camera_id: int, width: int, height: int, rgb_bytes: bytes):
        try:
            modelo = self.red.modelo
            if (width, height) != (modelo.image_width, modelo.image_height):
                rgb_bytes = resize_nearest(rgb_bytes, width, height, modelo.image_width, modelo.image_height)
                width, height = modelo.image_width, modelo.image_height

            entrada = [b / 255.0 for b in rgb_bytes]
            clase, confianza = self.red.predict_class(entrada)

            if confianza < self.config.confianza_minima:
                print("[Camara %d] deteccion descartada (%s, confianza=%.2f < %.2f)" %
                      (camera_id, clase, confianza, self.config.confianza_minima))
                return

            timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            timestamp_archivo = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            nombre_archivo = "cam%d_%s_%s.bmp" % (camera_id, clase, timestamp_archivo)
            ruta_imagen = os.path.join(self.config.capturas_dir, nombre_archivo)
            save_bmp(ruta_imagen, width, height, rgb_bytes)

            indice = proponer_evento(self.cluster_config, camera_id, clase, timestamp, ruta_imagen)
            if indice is not None:
                print("[Camara %d] DETECTADO '%s' (confianza=%.2f) -> log #%d -> %s" %
                      (camera_id, clase, confianza, indice, ruta_imagen))
            else:
                print("[Camara %d] DETECTADO '%s' pero el cluster Raft no respondio "
                      "(revisar que los nodos esten corriendo)" % (camera_id, clase))
        except Exception:
            print("[Camara %d] Error procesando frame:" % camera_id)
            traceback.print_exc()
