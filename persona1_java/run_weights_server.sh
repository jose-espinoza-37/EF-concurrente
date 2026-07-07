#!/bin/bash
# Levanta el servidor que expone por socket el archivo de pesos ya entrenado
# (para que el Servidor de Testeo en Python lo descargue).
set -e
cd "$(dirname "$0")"
java -cp out cc4p1.pesos.WeightsServerMain config/training.properties
