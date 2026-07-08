"""
Uso:
    python main.py [ruta_testing.properties]
"""

import sys

from testeo.servidor_testeo import ServidorDeTesteo


def main():
    config_path = sys.argv[1] if len(sys.argv) > 1 else "config/testing.properties"
    servidor = ServidorDeTesteo(config_path)
    servidor.iniciar()


if __name__ == "__main__":
    main()
