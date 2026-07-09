# Sistema Distribuido de Reconocimiento de Objetos con Consenso Raft

**CC4P1 Programacion Concurrente y Distribuida — Final 2026-I**

Sistema distribuido que entrena y consume modelos de inteligencia artificial para reconocer objetos, animales y personas a traves de camaras IP, garantizando persistencia, accesibilidad y tolerancia a fallos mediante el algoritmo de consenso Raft implementado en un cluster heterogeneo de 3 lenguajes.

---

## Arquitectura General

```
                      CAMARAS IP (minimo 3)
       +-----------+   +-----------+   +-----------+
       | Camara 1  |   | Camara 2  |   | Camara 3  |
       | (Celular) |   |  (Tablet) |   | (Celular) |
       +-----+-----+   +-----+-----+   +-----+-----+
             |               |               |
             +-------+-------+-------+-------+
                     | frames via socket TCP
                     v
  +--------------------------------------------------+
  |    SERVIDOR DE TESTEO DE OBJETOS (Python)         |
  |    - Recibe frames de 3 camaras (1 hilo x cam)   |
  |    - Descarga modelo entrenado desde Java         |
  |    - Ejecuta inferencia (red neuronal)            |
  |    - Guarda imagen + propone evento al cluster    |
  +------------------------+-------------------------+
                           | PROPOSE via socket TCP
                           v
  +------------------------------------------------------+
  |           MODULO DE CONSENSO RAFT (heterogeneo)       |
  |                                                       |
  |  Nodo #1 (Java)    Nodo #2 (Python)   Nodo #3 (C++)  |
  |  +-----------+     +-----------+      +-----------+  |
  |  | Log       |<--->| Log       |<---->| Log       |  |
  |  | Maquina   |     | Maquina   |      | Maquina   |  |
  |  | de Estado |     | de Estado |      | de Estado |  |
  |  +-----------+     +-----------+      +-----------+  |
  |       Mismo protocolo de wire por socket TCP          |
  +------------------------+-----------------------------+
                           | GET_LOG
                           v
  +------------------------------------------------------+
  |       CLIENTE VIGILANTE DE OBJETOS (C++)              |
  |       Interfaz web con auto-refresco                  |
  |  - Lee registro replicado desde cualquier nodo Raft   |
  |  - Muestra tabla: foto, tipo, camara, fecha/hora      |
  |  - Tolerante a fallos (reconecta si un nodo cae)      |
  +------------------------------------------------------+

  +------------------------------------------------------+
  |     SERVIDOR DE ENTRENAMIENTO DE IA (Java)            |
  |  - Entrena red neuronal (n=4 clases) en paralelo      |
  |  - Persiste pesos en formato binario propio            |
  |  - Distribuye modelo via socket (GET_WEIGHTS)          |
  +------------------------------------------------------+
```

---

## Componentes del Sistema

### 1. Servidor de Entrenamiento (Java — Persona 1)

Entrena una red neuronal de 2 capas (perceptron multicapa) para reconocer 4 clases: `persona`, `perro`, `gato`, `carro`.

| Caracteristica | Detalle |
|---|---|
| Red neuronal | Input 3072 (32x32x3) -> Hidden 64 (sigmoid) -> Output 4 (softmax) |
| Entrenamiento | Data-parallel SGD: N hilos calculan gradientes en paralelo, hilo principal agrega |
| Persistencia | Formato binario propio con magic `0x43433450` ("CC4P") |
| Distribucion | Servidor de sockets en puerto 6100 (protocolo `GET_WEIGHTS` / `WEIGHTS_META`) |
| Dependencias | Solo JDK 8+ (java.net, java.io, java.util.concurrent, javax.imageio) |

### 2. Servidor de Testeo (Python — Persona 2)

Recibe video de 3 camaras IP, ejecuta inferencia con el modelo entrenado, y propone cada deteccion al cluster Raft.

| Caracteristica | Detalle |
|---|---|
| Camaras | 3 receptores independientes, 1 hilo por camara, auto-reconexion |
| Inferencia | Forward-pass de la misma red neuronal (solo pesos, sin reentrenar) |
| Imagenes | BMP writer manual (sin PIL/OpenCV), resize nearest-neighbor |
| Cliente Raft | Patrón try-node -> follow-redirect para encontrar al lider |
| Dependencias | Solo stdlib Python 3.8+ (socket, struct, threading, math) |

### 3. Nodo Raft (Java + Python + C++ — Los 3 integrantes)

Cluster de consenso heterogeneo donde cada nodo esta implementado en un lenguaje diferente, todos hablando el mismo protocolo de wire.

| Caracteristica | Detalle |
|---|---|
| Algoritmo | Raft completo: eleccion de lider, replicacion de log, commit por mayoria |
| Tolerancia | Soporta caida de 1 nodo (quorum 2 de 3) |
| Eleccion | Timeout aleatorio 1.5-3s, heartbeat cada 500ms |
| Concurrencia | RPCs a peers en paralelo (thread pool), mutex/lock sobre estado compartido |
| Protocolo | Frame [4B longitud big-endian][payload UTF-8], mensajes separados por `\|` |

### 4. Cliente Vigilante (C++ — Persona 3)

Interfaz web que muestra el registro de detecciones consultando el cluster Raft en tiempo real.

| Caracteristica | Detalle |
|---|---|
| Interfaz | Servidor HTTP sirviendo HTML con auto-refresco cada 3 segundos |
| Datos | Polling periodico de GET_LOG a los nodos del cluster |
| Visualizacion | Tabla con: #, foto, tipo (con badge de color), camara, fecha/hora |
| Imagenes | Sirve archivos BMP via HTTP directamente |
| Dependencias | Solo C++17 stdlib + POSIX sockets |

---

## Protocolo de Comunicacion

### Wire Framing (compartido por los 3 lenguajes)

```
+-------------------+---------------------------+
| 4 bytes           | N bytes                   |
| longitud payload  | payload UTF-8             |
| (big-endian)      |                           |
+-------------------+---------------------------+
```

### Mensajes Raft (peer-to-peer)

| Codigo | Formato | Descripcion |
|---|---|---|
| `RV` | `RV\|term\|candidateId\|lastLogIndex\|lastLogTerm` | Solicitud de voto |
| `RVR` | `RVR\|term\|true/false` | Respuesta a solicitud de voto |
| `AE` | `AE\|term\|leaderId\|prevLogIndex\|prevLogTerm\|leaderCommit\|entries` | AppendEntries / heartbeat |
| `AER` | `AER\|term\|true/false\|matchIndex` | Respuesta a AppendEntries |

### Mensajes Cliente

| Codigo | Formato | Descripcion |
|---|---|---|
| `PROPOSE` | `PROPOSE\|camera\|clase\|timestamp\|imagePath` | Proponer nueva deteccion |
| `PROPOSE_OK` | `PROPOSE_OK\|index` | Deteccion aceptada |
| `PROPOSE_REDIRECT` | `PROPOSE_REDIRECT\|host:port` | Redirigir al lider |
| `GET_LOG` | `GET_LOG` | Solicitar registro completo |
| `LOG_DATA` | `LOG_DATA\|entry1~entry2~...` | Registro de detecciones |

### Formato de Log Entry

Campos separados por `,` entre entradas, entradas separadas por `~`:

```
index,term,camera,clase,timestamp,imagePath
```

Ejemplo: `1,1,cam1,persona,2026-07-09_10:30:00,detecciones/cam1_persona.bmp`

### Distribucion de Pesos

| Codigo | Formato | Descripcion |
|---|---|---|
| `GET_WEIGHTS` | `GET_WEIGHTS` | Solicitar modelo |
| `WEIGHTS_META` | `WEIGHTS_META\|filesize` | Metadatos del modelo |
| *(raw bytes)* | *(filesize bytes)* | Archivo binario del modelo |

---

## Estructura de Archivos

```
EF-concurrente/
|
+-- README.md                          <- Este archivo
+-- test.md                            <- Guia de pruebas del sistema completo
+-- arquitectura_cc4p1.md              Diseno y distribucion de tareas
+-- estructura_proyecto.md             Referencia de estructura por persona
+-- Final_cc4P1-611_v13.pdf            Enunciado del proyecto
+-- .gitignore
|
+-- persona1_java/                     PERSONA 1 — Java (Ubuntu)
|   +-- build.sh                       Compila con javac
|   +-- run_training.sh                Ejecuta entrenamiento
|   +-- run_weights_server.sh          Servidor de distribucion de pesos
|   +-- run_raft_node.sh               Nodo Raft #1 (cluster real)
|   +-- run_raft_local_test.sh         Test local: 3 nodos Java
|   +-- config/
|   |   +-- training.properties        Hiperparametros del entrenamiento
|   |   +-- cluster.properties         Topologia del cluster (IPs/puertos)
|   |   +-- cluster-local-test.properties
|   +-- dataset/
|   |   +-- persona/ perro/ gato/ carro/   Imagenes de entrenamiento
|   +-- modelo/
|   |   +-- pesos_modelo.bin           Modelo entrenado (generado)
|   +-- src/cc4p1/
|       +-- common/
|       |   +-- Frame.java             Wire framing [4B + payload]
|       |   +-- ProtocolMessage.java   Codigos de mensaje
|       +-- entrenamiento/
|       |   +-- Main.java              Orquestador del entrenamiento paralelo
|       |   +-- NeuralNetwork.java     Red neuronal (MLP + backprop)
|       |   +-- ImageDataset.java      Carga de imagenes
|       |   +-- ModelPersistence.java   Formato binario del modelo
|       |   +-- Sample.java            Vector de features + etiqueta
|       |   +-- TrainingConfig.java    Parser de configuracion
|       +-- pesos/
|       |   +-- WeightsServer.java     Socket server para distribuir modelo
|       |   +-- WeightsServerMain.java
|       +-- raft/
|           +-- RaftNode.java          Algoritmo Raft completo
|           +-- RaftServer.java        TCP listener + dispatcher
|           +-- PeerClient.java        RPC client con timeout
|           +-- StateMachine.java      Registro thread-safe (RWLock)
|           +-- LogEntry.java          Entrada del log replicado
|           +-- RaftConfig.java        Parser de cluster config
|           +-- RaftState.java         Enum FOLLOWER/CANDIDATE/LEADER
|           +-- RaftNodeMain.java
|
+-- persona2_python/                   PERSONA 2 — Python (Windows)
|   +-- run_raft_node.sh / .bat        Nodo Raft #2
|   +-- run_testing_server.sh / .bat   Servidor de Testeo
|   +-- run_camera_sim.sh / .bat       Simulador de camara
|   +-- requirements.txt               (vacio: solo stdlib)
|   +-- config/
|   |   +-- cluster.properties
|   |   +-- cluster-local-test.properties
|   |   +-- testing.properties         Camaras, pesos, umbral
|   |   +-- testing_local_test.properties
|   +-- src/
|       +-- common/
|       |   +-- frame.py               Wire framing (identico a Java)
|       |   +-- protocol.py            Codigos de mensaje
|       |   +-- bmp.py                 Escritor BMP manual
|       |   +-- image_utils.py         Resize nearest-neighbor
|       +-- modelo/
|       |   +-- model_loader.py        Parser del formato binario de Java
|       |   +-- red_neuronal.py        Forward-pass (solo inferencia)
|       |   +-- pesos_cliente.py       Descarga modelo via GET_WEIGHTS
|       +-- camaras/
|       |   +-- receptor_camara.py     Receptor de 3 camaras (1 hilo c/u)
|       |   +-- camara_cliente_movil.py  Simulador de camara
|       +-- testeo/
|       |   +-- servidor_testeo.py     Orquestador: inferencia + evento
|       |   +-- raft_client_helper.py  Cliente Raft con redirect
|       |   +-- testing_config.py      Parser de config
|       |   +-- main.py
|       +-- raft/
|           +-- raft_node.py           Algoritmo Raft completo
|           +-- raft_server.py         TCP listener
|           +-- peer_client.py         RPC client
|           +-- state_machine.py       Registro thread-safe
|           +-- log_entry.py           Entrada del log
|           +-- raft_config.py         Parser de config
|           +-- raft_node_main.py
|
+-- persona3_cpp/                      PERSONA 3 — C++ (Ubuntu)
    +-- build.sh                       Compila con g++ -std=c++17
    +-- run_raft_node.sh               Nodo Raft #3 (cluster real)
    +-- run_vigilante.sh               Cliente Vigilante (HTTP)
    +-- run_local_test.sh              Test local: 3 nodos C++ + vigilante
    +-- config/
    |   +-- cluster.properties
    |   +-- cluster-local-test.properties
    |   +-- vigilante.properties       Config del Cliente Vigilante
    |   +-- vigilante_local_test.properties
    +-- src/
        +-- common/
        |   +-- frame.h / frame.cpp    Wire framing (identico a Java/Python)
        |   +-- protocol.h             Codigos de mensaje
        |   +-- utils.h                Helpers: split, trim, loadProperties
        +-- raft/
        |   +-- raft_node.h / .cpp     Algoritmo Raft completo
        |   +-- raft_server.h / .cpp   TCP listener
        |   +-- peer_client.h / .cpp   RPC client con timeout
        |   +-- state_machine.h / .cpp Registro thread-safe (shared_mutex)
        |   +-- log_entry.h            Entrada del log
        |   +-- raft_config.h / .cpp   Parser de config
        |   +-- main.cpp
        +-- vigilante/
            +-- http_server.h / .cpp   Servidor HTTP (HTML + imagenes)
            +-- raft_log_client.h/.cpp Polling GET_LOG al cluster
            +-- vigilante_config.h/.cpp
            +-- main.cpp
```

---

## Puertos del Sistema

| Servicio | Puerto | Lenguaje |
|---|---|---|
| Nodo Raft #1 | 6001 | Java |
| Nodo Raft #2 | 6002 | Python |
| Nodo Raft #3 | 6003 | C++ |
| Servidor de Pesos | 6100 | Java |
| Camara 1 | 7101 | Python (receptor) |
| Camara 2 | 7102 | Python (receptor) |
| Camara 3 | 7103 | Python (receptor) |
| Cliente Vigilante HTTP | 8080 | C++ |

---

## Requisitos por Lenguaje

| Lenguaje | Version minima | SO principal | Dependencias externas |
|---|---|---|---|
| Java | JDK 8+ | Ubuntu (SO1) | Ninguna (solo JDK) |
| Python | 3.8+ | Windows (SO2) | Ninguna (solo stdlib) |
| C++ | g++ con C++17 | Ubuntu (SO1) | Ninguna (solo stdlib + POSIX) |

---

## Guia Rapida de Ejecucion

### Orden de inicio recomendado

```
1. Entrenar modelo      (Java)     ->  persona1_java/run_training.sh
2. Servidor de pesos    (Java)     ->  persona1_java/run_weights_server.sh
3. Nodo Raft #1         (Java)     ->  persona1_java/run_raft_node.sh
4. Nodo Raft #2         (Python)   ->  persona2_python/run_raft_node.sh
5. Nodo Raft #3         (C++)      ->  persona3_cpp/run_raft_node.sh
6. Simuladores camara   (Python)   ->  persona2_python/run_camera_sim.sh 1
7. Servidor de testeo   (Python)   ->  persona2_python/run_testing_server.sh
8. Cliente Vigilante    (C++)      ->  persona3_cpp/run_vigilante.sh
9. Abrir navegador                 ->  http://localhost:8080
```

### Antes de ejecutar

1. **Compilar Java**: `cd persona1_java && ./build.sh`
2. **Compilar C++**: `cd persona3_cpp && ./build.sh`
3. **Editar `cluster.properties`** de los 3 proyectos con las IPs reales de cada maquina
4. Python no requiere compilacion ni dependencias

---

## Tolerancia a Fallos

El cluster Raft tolera la caida de **1 nodo de 3** manteniendo el quorum (2 de 3):

- Si el **lider** cae, los 2 nodos restantes detectan el timeout de heartbeat, inician eleccion, y un nuevo lider es elegido en 2-3 segundos
- Las entradas ya **confirmadas** (commit) nunca se pierden
- El **Cliente Vigilante** reconecta automaticamente al siguiente nodo disponible
- El **Servidor de Testeo** sigue la redireccion PROPOSE_REDIRECT al nuevo lider

---

## Contrato entre los 3 Lenguajes

Para que el cluster heterogeneo funcione, estos archivos implementan **exactamente el mismo protocolo**:

| Contrato | Java | Python | C++ |
|---|---|---|---|
| Wire framing | `Frame.java` | `frame.py` | `frame.h/.cpp` |
| Codigos de mensaje | `ProtocolMessage.java` | `protocol.py` | `protocol.h` |
| Topologia cluster | `cluster.properties` | `cluster.properties` | `cluster.properties` |
| Formato modelo (binario) | `ModelPersistence.java` | `model_loader.py` | N/A |

---

## Equipo

| Rol | Lenguaje | Responsabilidades |
|---|---|---|
| Persona 1 | Java | Entrenamiento IA + Servidor de Pesos + Nodo Raft #1 |
| Persona 2 | Python | Camaras + Servidor de Testeo + Nodo Raft #2 |
| Persona 3 | C++ | Cliente Vigilante + Nodo Raft #3 |
