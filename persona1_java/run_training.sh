#!/bin/bash
# Ejecuta el Servidor de Entrenamiento de IA (entrena y persiste los pesos).
set -e
cd "$(dirname "$0")"
java -cp out cc4p1.entrenamiento.Main config/training.properties
