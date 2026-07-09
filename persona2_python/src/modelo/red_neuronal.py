

import math
from typing import List

from modelo.model_loader import ModeloCargado


def _sigmoid(x: float) -> float:
    # recorte para evitar overflow de math.exp con valores muy negativos
    if x < -60:
        return 0.0
    return 1.0 / (1.0 + math.exp(-x))


def _softmax(logits: List[float]) -> List[float]:
    m = max(logits)
    exps = [math.exp(v - m) for v in logits]
    total = sum(exps)
    return [v / total for v in exps]


class RedNeuronal:
    """Envoltorio de inferencia sobre un ModeloCargado (ver model_loader.py)."""

    def __init__(self, modelo: ModeloCargado):
        self.modelo = modelo

    def predict(self, entrada: List[float]) -> List[float]:
        """Devuelve el vector de probabilidades (softmax) por clase."""
        m = self.modelo
        if len(entrada) != m.input_size:
            raise ValueError(
                "Tamano de entrada invalido: se esperaban %d valores, llegaron %d"
                % (m.input_size, len(entrada))
            )

        # --- capa oculta ---
        hidden = [0.0] * m.hidden_size
        for j in range(m.hidden_size):
            s = m.b_h[j]
            row = m.w_ih[j]
            for i in range(m.input_size):
                s += row[i] * entrada[i]
            hidden[j] = _sigmoid(s)

        # --- capa de salida ---
        logits = [0.0] * m.output_size
        for k in range(m.output_size):
            s = m.b_o[k]
            row = m.w_ho[k]
            for j in range(m.hidden_size):
                s += row[j] * hidden[j]
            logits[k] = s

        return _softmax(logits)

    def predict_class(self, entrada: List[float]):
        """Devuelve (nombre_de_clase, confianza) de la prediccion mas probable."""
        probs = self.predict(entrada)
        best_idx = max(range(len(probs)), key=lambda i: probs[i])
        return self.modelo.class_names[best_idx], probs[best_idx]
