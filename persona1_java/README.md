# Persona 1 — Java (Ubuntu)
## CC4P1 — Servidor de Entrenamiento de IA + Nodo Raft #1

Esta carpeta contiene **todo el código que le corresponde a la Persona 1** dentro
del proyecto distribuido:

1. **Servidor de Entrenamiento de IA** (obligatorio en Java) — entrena una red
   neuronal para reconocer n=4 clases (`persona`, `perro`, `gato`, `carro`) y
   persiste los pesos en disco.
2. **Servidor de Pesos** — expone por socket el modelo ya entrenado, para que
   el Servidor de Testeo (Python, Persona 2) lo descargue sin necesitar un
   filesystem compartido.
3. **Nodo Raft #1** — el nodo Java del clúster de consenso heterogéneo
   (Java + Python + C++) descrito en la arquitectura general del proyecto.

Todo está escrito **solo con librerías base del JDK** (`java.net`, `java.io`,
`java.util.concurrent`, `javax.imageio`, `java.awt`) — nada de frameworks,
websockets, MQ ni librerías externas de ML, tal como exige el enunciado.

---

## 1. Requisitos

- **Java 8 o superior** (JDK, no solo JRE — se necesita `javac` para compilar).
  Verificar con:
  ```bash
  java -version
  javac -version
  ```
- Sistema operativo: pensado para correr en **Ubuntu** (nodo `java1` del
  clúster), aunque el código es puro Java y también corre en Windows/Mac si
  hace falta probarlo ahí.
- No requiere internet ni ninguna dependencia externa (Maven, Gradle, etc.).
  Todo se compila con `javac` directo.
- Puertos usados (deben estar libres y abiertos en el firewall de la LAN):
  - `6001` → Nodo Raft #1 (Java)
  - `6100` → Servidor de Pesos

---

## 2. Estructura de carpetas

```
persona1_java/
├── README.md                    (este archivo)
├── build.sh                     compila todo el proyecto
├── run_training.sh              ejecuta el entrenamiento
├── run_weights_server.sh        levanta el servidor de pesos
├── run_raft_node.sh             levanta el Nodo Raft #1 (cluster real, LAN)
├── run_raft_local_test.sh       prueba de humo: 3 nodos Raft locales (solo Java)
├── config/
│   ├── training.properties      hiperparámetros y rutas del entrenamiento
│   ├── cluster.properties       IPs/puertos del clúster real (Java+Python+C++)
│   └── cluster-local-test.properties   config para probar Raft solo en localhost
├── dataset/
│   ├── persona/  perro/  gato/  carro/   (poner aquí las fotos reales)
├── modelo/                       aquí se guarda pesos_modelo.bin al entrenar
└── src/cc4p1/
    ├── common/       protocolo de wire compartido (framing + mensajes)
    ├── entrenamiento/  red neuronal, carga de dataset, persistencia
    ├── pesos/          servidor de sockets que distribuye el modelo
    └── raft/           el algoritmo de consenso Raft completo
```

---

## 3. Cómo ejecutarlo

### Paso 0 — Compilar
```bash
cd persona1_java
./build.sh
```
Esto genera las clases en `./out`. Repetir cada vez que se modifique el código.

### Paso 1 — Entrenar el modelo
```bash
./run_training.sh
```
- Lee las imágenes de `dataset/<clase>/`.
- **Si alguna carpeta de clase está vacía**, el programa genera automáticamente
  imágenes sintéticas de prueba (formas de color con ruido) para poder correr
  el pipeline completo sin depender de fotos reales. Para la entrega deben
  reemplazar el contenido de `dataset/<clase>/` por fotografías verdaderas.
- Entrena durante `epochs` épocas (configurable en `config/training.properties`)
  usando varios hilos en paralelo.
- Al terminar, guarda `modelo/pesos_modelo.bin`.

Salida esperada (resumida):
```
Epoca    1/200  loss=1.66  accuracy=25.0%
...
Epoca  200/200  loss=0.04  accuracy=100.0%
Entrenamiento finalizado en 12827 ms.
[ModelPersistence] Modelo guardado en: .../modelo/pesos_modelo.bin (1575517 bytes)
```

### Paso 2 — Levantar el servidor de pesos
```bash
./run_weights_server.sh
```
Queda escuchando en el puerto `6100` (configurable), listo para que el
Servidor de Testeo (Python) pida `GET_WEIGHTS` y descargue el modelo.

### Paso 3 — Levantar el Nodo Raft #1
Antes de correr en la LAN real, **editar `config/cluster.properties`** con las
IPs verdaderas de las 3 máquinas (Java/Ubuntu, Python/Windows, C++/Ubuntu):
```properties
node.java1.host=192.168.1.10
node.python1.host=192.168.1.20
node.cpp1.host=192.168.1.10
```
Luego, en la máquina Ubuntu de la Persona 1:
```bash
./run_raft_node.sh
```

### (Opcional) Probar Raft antes de integrar Python/C++
Para validar que la lógica de consenso funciona sin depender todavía de los
otros dos lenguajes, se puede levantar un mini-clúster de 3 nodos, todos en
Java, todos en `localhost`:
```bash
./run_raft_local_test.sh
```
Esto arranca 3 procesos escuchando en `7001`, `7002` y `7003`. Se puede
enviar mensajes de prueba manualmente con `netcat`/un script propio siguiendo
el protocolo de la sección 5, o simplemente observar en la consola cómo un
nodo se autoproclama líder cada vez que reinician los timeouts.

Esta prueba end-to-end **ya se ejecutó y verificó** durante el desarrollo:
elección de líder, `PROPOSE` con redirección automática al líder,
replicación a los followers, y **tolerancia real a la caída del líder**
(al matar el proceso líder, el clúster reeligió a otro nodo en ~2-3s sin
perder ninguna entrada del log ya confirmada).

---

## 4. Lógica del sistema (resumen)

### 4.1 Entrenamiento (`cc4p1.entrenamiento`)
1. `ImageDataset` carga cada imagen, la redimensiona a 32×32 y la aplana en
   un vector de 3072 valores normalizados (R,G,B por píxel).
2. `NeuralNetwork` es un perceptrón multicapa (entrada → oculta sigmoide →
   salida softmax) escrito solo con arrays de `double[][]`, sin librerías de
   ML externas.
3. `Main` reparte las muestras en N *shards* (uno por hilo, `batch.threads`
   en la config) y en cada época:
   - **N hilos calculan gradientes en paralelo** (`computeGradients`, que es
     de solo-lectura sobre los pesos — así no hay condiciones de carrera).
   - El hilo principal **suma** esos gradientes y aplica **una sola**
     actualización de pesos por época (`applyGradients`).

   Esto es lo que cumple el requisito de "carga de trabajo distribuida entre
   nodos/hilos para optimizar el entrenamiento" del enunciado, usando
   concurrencia real de Java (`ExecutorService`).
4. `ModelPersistence` guarda/lee los pesos en un **formato binario propio**
   documentado byte a byte en el propio archivo fuente, para que Persona 2
   (Python) pueda leerlo con `struct`.

### 4.2 Servidor de Pesos (`cc4p1.pesos`)
Un `ServerSocket` simple: cuando alguien manda `GET_WEIGHTS`, responde con el
tamaño del archivo y luego los bytes crudos del modelo. Atiende conexiones
concurrentes con un pool de hilos (`newCachedThreadPool`).

### 4.3 Nodo Raft (`cc4p1.raft`) — la parte más importante
- **`RaftNode`** es el corazón: máquina de estados FOLLOWER → CANDIDATE →
  LEADER, con:
  - Timer de elección aleatorio (1.5–3s) protegido por un `ReentrantLock`
    para que no se corrompa el estado si dos hilos (timer y conexión
    entrante) lo tocan a la vez.
  - Heartbeats cada 500ms cuando es líder.
  - Envío de `RequestVote`/`AppendEntries` a **todos los peers en paralelo**
    (`ExecutorService`), para que un peer caído no bloquee la elección.
  - Replicación de log con conteo de mayoría (`recalculateCommitIndexLocked`)
    antes de aplicar una entrada a la máquina de estados.
- **`StateMachine`** guarda la lista final de "encuentros" (lo que finalmente
  ve el Cliente Vigilante), protegida con un `ReadWriteLock` para permitir
  lecturas concurrentes sin bloquearse entre sí.
- **`RaftServer`** acepta conexiones TCP (peers y clientes) con un hilo por
  conexión, y despacha cada mensaje al método de `RaftNode` correspondiente.
- **`PeerClient`** abre una conexión corta hacia un peer y espera respuesta
  con timeout — si el peer no contesta, se trata como una caída silenciosa
  (así es como Raft tolera fallos sin bloquearse).

### 4.4 Protocolo de wire (`cc4p1.common`) — el contrato entre los 3 lenguajes
- **`Frame`**: cada mensaje va precedido de 4 bytes (longitud, big-endian).
  Python y C++ deben implementar exactamente este mismo framing.
- **`ProtocolMessage`**: mensajes de texto separados por `|`, por ejemplo:
  - `PROPOSE|camara|clase|fechaHora|rutaImagen` (Testeo → líder)
  - `AE|term|leaderId|prevLogIndex|prevLogTerm|leaderCommit|entradas` (peer a peer)
  - `GET_LOG` → `LOG_DATA|cantidad|entrada1~entrada2~...` (Vigilante → cualquier nodo)

  Este es el mismo protocolo que deben hablar los nodos de Persona 2 (Python)
  y Persona 3 (C++) para poder integrarse al mismo clúster Raft.

---

## 5. Archivos más importantes (por si necesitan revisar/depurar algo puntual)

| Archivo | Por qué es importante |
|---|---|
| `raft/RaftNode.java` | Toda la lógica de consenso: elección, heartbeats, replicación, commit. Es el archivo más denso y el más importante de todo el proyecto. |
| `raft/RaftServer.java` | Punto de entrada de red del nodo Raft; aquí se ve el "dispatch" de cada tipo de mensaje. |
| `common/Frame.java` + `common/ProtocolMessage.java` | El contrato exacto que Python y C++ deben replicar para que el clúster heterogéneo funcione. |
| `entrenamiento/NeuralNetwork.java` | La red neuronal y el paralelismo del cálculo de gradientes. |
| `entrenamiento/ModelPersistence.java` | El formato binario del modelo — imprescindible para que Persona 2 lo lea correctamente. |
| `pesos/WeightsServer.java` | Cómo se entrega el modelo a otra máquina/lenguaje por socket. |

---

## 6. Notas para la integración con Persona 2 (Python) y Persona 3 (C++)

- Compartir `config/cluster.properties` (con las IPs reales) entre los 3 para
  que todos apunten al mismo clúster.
- El formato de `pesos_modelo.bin` está documentado en el encabezado de
  `ModelPersistence.java` — es lo primero que debe replicar el parser de
  Python.
- El framing de 4 bytes + payload de `Frame.java` y los códigos de mensaje de
  `ProtocolMessage.java` son el contrato mínimo que deben implementar los
  nodos Raft de Python y C++ para hablar el mismo idioma dentro del clúster.
