#!/bin/bash
# Compila todo el codigo fuente Java del proyecto (Persona 1).
# Requiere JDK 8 o superior (javac en el PATH).
set -e
cd "$(dirname "$0")"

echo "Compilando..."
rm -rf out
mkdir -p out
find src -name "*.java" > .sources.txt
javac -d out -encoding UTF-8 @.sources.txt
rm -f .sources.txt

echo "OK. Clases generadas en ./out"
