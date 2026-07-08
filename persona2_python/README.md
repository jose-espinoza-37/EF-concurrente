# Persona 2 — Python (Windows)
## CC4P1 — Cámaras + Servidor de Testeo + Nodo Raft #2

Esta carpeta contiene **todo el código que le corresponde a la Persona 2**
dentro del proyecto distribuido:

1. **Recepción de las 3 cámaras IP** (celular/tablet) — un hilo por cámara.
2. **Servidor de Testeo de Objetos** — descarga el modelo entrenado por
   Persona 1 (Java), corre inferencia sobre cada frame, guarda la imagen
   detectada y propone el evento al clúster Raft.
3. **Nodo Raft #2** — el nodo Python del mismo clúster de consenso
   heterogéneo (Java + Python + C++) descrito en la arquitectura general.

Todo está escrito **solo con la biblioteca estándar de Python** (`socket`,
`struct`, `threading`, `concurrent.futures`, `dataclasses`, `math`,
`argparse`, `datetime`) — sin `pip install` de nada, ni frameworks de
comunicación, ni librerías externas de ML (numpy/PIL/OpenCV), tal como exige
el enunciado.

---

## 1. Requisitos

- **Python 3.8 o superior** (se usan `dataclasses` y tipado). Verificar con:
  ```
  python --version
  ```
- Sistema operativo: pensado para correr en **Windows** (nodo `python1` del
  clúster), aunque el código es Python puro y también corre en Linux/Mac.
- No requiere `pip install` de nada — ver `requirements.txt`.
- Puertos usados (deben estar libres y abiertos en el firewall de la LAN):
  - `6002` → Nodo Raft #2 (Python)
  - `7101`, `7102`, `7103` → las 3 cámaras IP (configurable)
- Debe poder alcanzar por red al **Servidor de Pesos de Java** (puerto
  `6100` por defecto) para descargar el modelo entrenado.

---

## 2. Estructura de carpetas

```
persona2_python/
├── README.md                        (este archivo)
├── requirements.txt                  (vacío a propósito: solo stdlib)
├── run_raft_node.sh / .bat           levanta el Nodo Raft #2
├── run_testing_server.sh / .bat      levanta el Servidor de Testeo
├── run_camera_sim.sh / .bat          simula una cámara (para probar sin hardware real)
│
├── config/
│   ├── cluster.properties            IPs/puertos del cluster real (Java+Python+C++)
│   ├── cluster-local-test.properties config para probar Raft solo en localhost
│   ├── testing.properties            cámaras, servidor de pesos, umbral
│   └── testing_local_test.properties config para la prueba end-to-end local
│
├── capturas/                         aquí se guardan las imágenes detectadas (.bmp)
│
└── src/
    ├── common/
    │   ├── frame.py                  framing (IDÉNTICO a Frame.java)
    │   ├── protocol.py                códigos de mensaje (IDÉNTICOS a ProtocolMessage.java)
    │   ├── bmp.py                     escritor de imágenes BMP hecho a mano
    │   └── image_utils.py             resize nearest-neighbor sin librerías externas
    │
    ├── modelo/
    │   ├── model_loader.py            parsea pesos_modelo.bin (MISMO formato que Java)
    │   ├── red_neuronal.py            forward-pass de la red (solo inferencia)
    │   └── pesos_cliente.py           descarga el modelo desde el WeightsServer (Java)
    │
    ├── camaras/
    │   ├── receptor_camara.py         recibe frames de las 3 cámaras (1 hilo c/u)
    │   └── camara_cliente_movil.py    simulador de cámara (celular/tablet)
    │
    ├── testeo/
    │   ├── testing_config.py          lee testing.properties
    │   ├── raft_client_helper.py      PROPOSE con redirección automática al líder
    │   ├── servidor_testeo.py         orquesta todo lo anterior
    │   └── main.py                    entry point
    │
    └── raft/
        ├── raft_config.py             lee cluster.properties
        ├── log_entry.py               una entrada del log replicado
        ├── state_machine.py           registro final (lo que ve el Vigilante)
        ├── peer_client.py             RPC saliente hacia otro nodo Raft
        ├── raft_node.py               ⭐ el algoritmo Raft completo
        ├── raft_server.py             acepta conexiones entrantes (peers/clientes)
        └── raft_node_main.py          entry point del Nodo Raft #2
```

---

## 3. Cómo ejecutarlo

### Paso 0 — Verificar Python
No hace falta compilar nada ni instalar dependencias.
```
python --version   # 3.8 o superior
```

### Paso 1 — Levantar el Nodo Raft #2
Antes de correr en la LAN real, **editar `config/cluster.properties`** con
las IPs verdaderas de las 3 máquinas (igual que en Persona 1 y 3):
```properties
node.java1.host=192.168.1.10
node.python1.host=192.168.1.20
node.cpp1.host=192.168.1.10
```
Luego, en la máquina Windows de la Persona 2:
```
run_raft_node.bat python1 config\cluster.properties
```
(en Linux/Mac para pruebas: `./run_raft_node.sh python1 config/cluster.properties`)

### Paso 2 — Levantar el Servidor de Testeo
Requiere que el **Servidor de Pesos de Java** ya esté corriendo (ver README
de Persona 1) y que las 3 cámaras estén enviando video. Editar
`config/testing.properties` con las IPs reales del servidor de pesos y de
las cámaras, y luego:
```
run_testing_server.bat
```

### (Opcional) Probar sin hardware real: cámaras simuladas
Mientras no se tenga acceso a los celulares/tablets reales, se puede simular
cada cámara con:
```
run_camera_sim.bat 1 7101
run_camera_sim.bat 2 7102
run_camera_sim.bat 3 7103
```
Esto genera frames sintéticos (igual de espíritu que la generación sintética
del entrenamiento en Java) para poder probar todo el resto del pipeline.
**Para el despliegue real, solo hay que reemplazar la fuente de los frames
por la captura real de cada dispositivo** — el resto del protocolo de red no
cambia (ver comentarios en `camara_cliente_movil.py`).

### Prueba end-to-end ya verificada durante el desarrollo
Se ejecutó el pipeline **completo y real** (no solo cada pieza por
separado): WeightsServer de Java + 3 nodos Raft + 3 cámaras simuladas +
Servidor de Testeo, todo al mismo tiempo. Resultado: el modelo se descargó
correctamente desde Java, las 3 cámaras se conectaron, cada frame se
clasificó con la red entrenada, cada detección se guardó como `.bmp` válido,
y **cada evento se propuso y confirmó en el clúster Raft** (log creciendo
correctamente entrada por entrada, visible por cualquier nodo).

Más importante todavía: se probó un **clúster Raft mixto real** con 1 nodo
Java + 2 nodos Python corriendo simultáneamente. Un nodo Java votó
correctamente por un candidato Python y lo reconoció como líder; los
mensajes `PROPOSE`/`GET_LOG` fueron atendidos indistintamente por cualquier
nodo sin importar su lenguaje; y al matar por la fuerza el proceso del líder
real (fuera Java o Python), el clúster restante reeligió y **no perdió
ninguna entrada ya confirmada** — confirmando que el protocolo diseñado en
la Parte 1 realmente interopera entre lenguajes.

---

## 4. Lógica del sistema (resumen)

### 4.1 Por qué no hay ninguna dependencia externa
La biblioteca estándar de Python, a diferencia del JDK, **no trae ningún
códec de imágenes ni acceso a cámara**. En vez de instalar Pillow/OpenCV
(no permitido por el enunciado), se resolvió con dos módulos propios:

- **`common/bmp.py`**: escribe archivos `.bmp` de 24 bits a mano con
  `struct` — el formato BMP sin compresión es solo un encabezado fijo +
  píxeles crudos, perfectamente escribible sin librerías.
- **`common/image_utils.py`**: un *resize* nearest-neighbor manual, para
  adaptar el tamaño del frame de la cámara al tamaño que espera el modelo
  (32×32), sin depender de ninguna librería de imágenes.

### 4.2 Cámaras (`camaras/`)
Cada cámara real (o su app en el celular/tablet) actúa como un pequeño
servidor de sockets; `receptor_camara.py` actúa como **cliente**: un hilo
por cámara se conecta y queda leyendo frames en bucle, con reconexión
automática si la cámara se desconecta — la caída de una cámara nunca
bloquea a las otras dos ni al resto del sistema.

`camara_cliente_movil.py` es, por ahora, un **simulador**: genera frames
sintéticos con un patrón de color distinto cada cierto tiempo, para poder
probar el pipeline completo sin depender de hardware real. Para el
despliegue real, solo se reemplaza la generación del frame; el protocolo de
transporte (framing + envío) permanece igual.

### 4.3 Inferencia (`modelo/`)
- **`model_loader.py`** implementa, con el módulo `struct` y el prefijo
  `">"` (big-endian), el lector **exacto** del formato binario que escribe
  `ModelPersistence.java`. Esto se verificó en la práctica: se descargó un
  modelo entrenado por Java y se leyó correctamente desde Python.
- **`red_neuronal.py`** reimplementa el mismo forward-pass que
  `NeuralNetwork.java` (capa oculta sigmoide, salida softmax) con listas
  planas de Python — no hace falta reentrenar nada aquí, solo usar los
  pesos ya calculados.

### 4.4 Servidor de Testeo (`testeo/`)
`servidor_testeo.py` es el orquestador: descarga el modelo al iniciar (con
reintento sobre una copia en caché si el servidor de pesos no responde),
levanta los receptores de cámara, y por cada frame corre inferencia, guarda
la imagen y llama a `raft_client_helper.proponer_evento(...)`.

Ese helper es importante: el Servidor de Testeo **no es un nodo Raft**, es
un **cliente** del clúster. Prueba un nodo cualquiera; si no es el líder,
seguirá la redirección (`PROPOSE_REDIRECT`) hasta encontrarlo, con reintentos
si el clúster está en medio de una elección.

### 4.5 Nodo Raft (`raft/`) — réplica fiel del protocolo de Java
Cada pieza de `raft/` es un espejo funcional de su equivalente en
`cc4p1.raft` (Java), pensado para hablar exactamente el mismo protocolo:

- **`raft_node.py`**: la máquina de estados FOLLOWER→CANDIDATE→LEADER, con
  un `threading.RLock` protegiendo el estado compartido (se eligió RLock,
  no un lock simple, porque algunas transiciones —como recibir una
  respuesta de replicación con un término mayor— piden el lock de nuevo
  desde el mismo hilo), un `ThreadPoolExecutor` para contactar a todos los
  peers **en paralelo** al pedir votos o replicar el log, y
  `threading.Timer` para el timeout de elección aleatorio.
- **`state_machine.py`**: el registro final de detecciones, protegido con
  un lock simple (a esta escala de datos no se necesita un
  read-write-lock como en Java: Python no lo trae en su biblioteca
  estándar y no aporta una ganancia medible aquí).
- **`raft_server.py`** / **`raft_node_main.py`**: el servidor de sockets y
  el punto de entrada ejecutable del nodo.

---

## 5. Archivos más importantes

| Archivo | Por qué es importante |
|---|---|
| `raft/raft_node.py` | Toda la lógica de consenso — el archivo más denso y más importante de esta parte. |
| `common/frame.py` + `common/protocol.py` | El mismo contrato que Java; si algo no calza aquí, el clúster heterogéneo deja de funcionar. |
| `modelo/model_loader.py` | El lector exacto del formato binario de Java — ya verificado en la práctica. |
| `common/bmp.py` | Cómo se resolvió "guardar imágenes sin librerías externas". |
| `testeo/raft_client_helper.py` | El patrón de "probar nodo → seguir redirect" que hace que el Servidor de Testeo siempre encuentre al líder actual. |

---

## 6. Notas para la integración con Persona 1 (Java) y Persona 3 (C++)

- Compartir `config/cluster.properties` (con las IPs reales) entre los 3.
- El puerto y host del Servidor de Pesos de Java deben configurarse en
  `config/testing.properties` (`weights.server.host` / `weights.server.port`).
- El protocolo de `common/frame.py` y `common/protocol.py` ya fue probado
  interoperando con la implementación real de Java (no solo diseñado en
  papel) — Persona 3 debe replicar ese mismo contrato en C++.
