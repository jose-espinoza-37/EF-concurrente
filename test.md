# Guia de Pruebas del Sistema Completo

Plan de pruebas para verificar todas las funcionalidades del sistema distribuido de reconocimiento de objetos con consenso Raft.

---

## Prerequisitos

### Compilacion

```bash
# Terminal 1: Compilar Java
cd persona1_java
./build.sh

# Terminal 2: Compilar C++
cd persona3_cpp
./build.sh
```

### Verificacion de entorno

```bash
# Java
java -version    # 8 o superior
javac -version

# Python
python3 --version   # 3.8 o superior

# C++
g++ --version   # soporte C++17

# Puertos libres
ss -tlnp | grep -E '600[1-3]|6100|710[1-3]|8080'
# No debe mostrar nada (puertos disponibles)
```

---

## FASE 1: Pruebas Unitarias por Componente

### Test 1.1 — Entrenamiento del modelo (Java)

**Objetivo**: Verificar que el entrenamiento produce un modelo valido.

```bash
cd persona1_java
./run_training.sh
```

**Resultado esperado**:
- [ ] Muestra progreso por epoca: `Epoca 1/200  loss=... accuracy=...`
- [ ] El accuracy sube progresivamente
- [ ] Al finalizar: `Entrenamiento finalizado en X ms`
- [ ] Se genera `modelo/pesos_modelo.bin` (archivo > 1MB)
- [ ] No hay errores ni excepciones

**Verificacion**:
```bash
ls -la persona1_java/modelo/pesos_modelo.bin
# Debe existir y tener tamaño > 1MB
```

---

### Test 1.2 — Servidor de Pesos (Java)

**Objetivo**: Verificar que el servidor distribuye el modelo correctamente.

```bash
# Terminal 1: Iniciar servidor
cd persona1_java
./run_weights_server.sh
```

**Resultado esperado**:
- [ ] Muestra: `WeightsServer escuchando en puerto 6100`
- [ ] No se cierra, queda esperando conexiones

**Verificacion manual** (en otra terminal):
```bash
# Simular peticion GET_WEIGHTS con Python
python3 -c "
import socket, struct
s = socket.socket()
s.connect(('127.0.0.1', 6100))
msg = b'GET_WEIGHTS'
s.sendall(struct.pack('>I', len(msg)) + msg)
# Leer respuesta
raw = s.recv(4)
length = struct.unpack('>I', raw)[0]
data = b''
while len(data) < length:
    data += s.recv(length - len(data))
print(f'Respuesta: {data.decode()[:50]}...')
# La respuesta debe empezar con WEIGHTS_META|
s.close()
"
```

- [ ] La respuesta comienza con `WEIGHTS_META|<tamaño>`

---

### Test 1.3 — Cluster Raft Local solo Java (3 nodos)

**Objetivo**: Verificar eleccion de lider y consenso con nodos Java.

```bash
cd persona1_java
./run_raft_local_test.sh
```

**Resultado esperado**:
- [ ] Los 3 nodos inician correctamente en puertos 7001, 7002, 7003
- [ ] Un nodo muestra: `Timeout -> me postulo a LIDER (term X)`
- [ ] Otros nodos muestran: `Voto concedido a ...`
- [ ] Un nodo se convierte en LEADER
- [ ] Los otros 2 reconocen al lider (FOLLOWER)
- [ ] Eleccion completa en < 5 segundos

---

### Test 1.4 — Cluster Raft Local solo Python (3 nodos)

**Objetivo**: Verificar eleccion de lider y consenso con nodos Python.

```bash
cd persona2_python

# Terminal 1
python3 src/raft/raft_node_main.py py_a config/cluster-local-test.properties &

# Terminal 2 (esperar 1s)
python3 src/raft/raft_node_main.py py_b config/cluster-local-test.properties &

# Terminal 3 (esperar 1s)
python3 src/raft/raft_node_main.py py_c config/cluster-local-test.properties &
```

**Resultado esperado**:
- [ ] Un nodo se elige como LEADER
- [ ] Los otros 2 son FOLLOWERS
- [ ] El estado se muestra periodicamente

---

### Test 1.5 — Cluster Raft Local solo C++ (3 nodos)

**Objetivo**: Verificar eleccion de lider y consenso con nodos C++.

```bash
cd persona3_cpp
./run_local_test.sh
```

**Resultado esperado**:
- [ ] 3 nodos inician en puertos 9001, 9002, 9003
- [ ] Eleccion de lider exitosa
- [ ] Vigilante inicia en http://localhost:8888
- [ ] La pagina web muestra "Sin detecciones registradas"

---

### Test 1.6 — Simulador de Camara (Python)

**Objetivo**: Verificar que el simulador genera y envia frames.

```bash
cd persona2_python
python3 src/camaras/camara_cliente_movil.py --id 1 --port 7101
```

**Resultado esperado**:
- [ ] El simulador inicia y queda escuchando conexiones
- [ ] Muestra puerto donde espera al receptor

---

## FASE 2: Pruebas de Integracion

### Test 2.1 — Descarga de modelo: Java -> Python

**Objetivo**: Verificar que Python descarga y parsea correctamente el modelo de Java.

```bash
# Terminal 1: Servidor de pesos Java
cd persona1_java
./run_weights_server.sh

# Terminal 2: Probar descarga desde Python
cd persona2_python
python3 -c "
from src.modelo.pesos_cliente import descargar_modelo
modelo = descargar_modelo('127.0.0.1', 6100, '/tmp/test_modelo.bin')
print(f'Modelo descargado: {modelo is not None}')
if modelo:
    print(f'Clases: {modelo.class_names}')
    print(f'Input size: {modelo.input_size}')
    print(f'Hidden size: {modelo.hidden_size}')
    print(f'Output size: {modelo.output_size}')
"
```

**Resultado esperado**:
- [ ] Modelo descargado exitosamente
- [ ] Clases: `['persona', 'perro', 'gato', 'carro']`
- [ ] Input size: 3072 (32x32x3)
- [ ] Hidden size: 64
- [ ] Output size: 4

---

### Test 2.2 — Cluster Raft Heterogeneo (Java + Python + C++)

**Objetivo**: Verificar que los 3 lenguajes forman un cluster Raft funcional.

**Configuracion**: Editar `cluster.properties` en los 3 proyectos para usar localhost:

```properties
cluster.nodes=java1,python1,cpp1
java1.host=127.0.0.1
java1.port=6001
python1.host=127.0.0.1
python1.port=6002
cpp1.host=127.0.0.1
cpp1.port=6003
```

```bash
# Terminal 1: Nodo Java
cd persona1_java
java -cp out cc4p1.raft.RaftNodeMain java1 config/cluster.properties

# Terminal 2: Nodo Python
cd persona2_python
python3 src/raft/raft_node_main.py python1 config/cluster.properties

# Terminal 3: Nodo C++
cd persona3_cpp
./build/raft_node cpp1 config/cluster.properties
```

**Resultado esperado**:
- [ ] Los 3 nodos se descubren mutuamente
- [ ] Un nodo es elegido LEADER (puede ser cualquiera de los 3)
- [ ] Los otros 2 reconocen al lider y se mantienen como FOLLOWERS
- [ ] El log periodico muestra Term, Lider, Log y Commit consistentes

---

### Test 2.3 — PROPOSE en Cluster Heterogeneo

**Objetivo**: Verificar replicacion de log entre los 3 lenguajes.

Con el cluster del Test 2.2 corriendo, enviar un PROPOSE:

```bash
python3 -c "
import socket, struct, time

def send_frame(s, data):
    encoded = data.encode('utf-8')
    s.sendall(struct.pack('>I', len(encoded)) + encoded)

def recv_frame(s):
    raw = b''
    while len(raw) < 4:
        raw += s.recv(4 - len(raw))
    length = struct.unpack('>I', raw)[0]
    data = b''
    while len(data) < length:
        data += s.recv(length - len(data))
    return data.decode('utf-8')

# Intentar PROPOSE a cada nodo hasta encontrar al lider
for port in [6001, 6002, 6003]:
    try:
        s = socket.socket()
        s.settimeout(2)
        s.connect(('127.0.0.1', port))
        send_frame(s, 'PROPOSE|cam1|persona|2026-07-09_14:00:00|det/persona.bmp')
        reply = recv_frame(s)
        s.close()
        print(f'Puerto {port}: {reply}')
        if reply.startswith('PROPOSE_OK'):
            print(f'  -> Lider encontrado en puerto {port}')
            break
        elif reply.startswith('PROPOSE_REDIRECT'):
            print(f'  -> Redireccion: {reply}')
    except Exception as e:
        print(f'Puerto {port}: Error - {e}')

# Esperar replicacion
time.sleep(1)

# Verificar GET_LOG en los 3 nodos
for port in [6001, 6002, 6003]:
    try:
        s = socket.socket()
        s.settimeout(2)
        s.connect(('127.0.0.1', port))
        send_frame(s, 'GET_LOG')
        reply = recv_frame(s)
        s.close()
        print(f'GET_LOG puerto {port}: {reply}')
    except Exception as e:
        print(f'GET_LOG puerto {port}: Error - {e}')
"
```

**Resultado esperado**:
- [ ] PROPOSE_OK recibido del lider
- [ ] Si no es lider, se recibe PROPOSE_REDIRECT con host:port correcto
- [ ] GET_LOG en los 3 nodos devuelve la MISMA entrada
- [ ] Formato: `LOG_DATA|1,1,cam1,persona,2026-07-09_14:00:00,det/persona.bmp`

---

### Test 2.4 — Cliente Vigilante con Cluster Heterogeneo

**Objetivo**: Verificar que la interfaz web muestra detecciones del cluster.

Con el cluster del Test 2.2 corriendo y al menos 1 PROPOSE enviado:

```bash
# Terminal 4: Iniciar vigilante
cd persona3_cpp
./build/vigilante config/vigilante.properties
```

**Verificacion**:
```bash
# Verificar respuesta HTTP
curl -s http://localhost:8080 | grep -c "badge"
# Debe mostrar al menos 1 (las detecciones con badges)

# Verificar que el titulo esta presente
curl -s http://localhost:8080 | grep "<title>"
# Debe mostrar: Cliente Vigilante - Sistema de Deteccion
```

**Verificacion visual**:
- [ ] Abrir http://localhost:8080 en el navegador
- [ ] Se muestra el titulo "Cliente Vigilante de Objetos"
- [ ] El contador muestra el numero correcto de detecciones
- [ ] La tabla muestra: #, Foto, Tipo (con badge de color), Camara, Fecha/Hora
- [ ] La pagina se auto-refresca cada 3 segundos
- [ ] Al enviar mas PROPOSEs, aparecen nuevas filas automaticamente

---

## FASE 3: Pruebas de Tolerancia a Fallos

### Test 3.1 — Caida del Lider

**Objetivo**: Verificar re-eleccion automatica cuando el lider cae.

Con el cluster heterogeneo corriendo (Test 2.2):

1. Identificar cual nodo es el LEADER (observar los logs)
2. **Matar el proceso del lider** (Ctrl+C o `kill`)
3. Observar los otros 2 nodos

**Resultado esperado**:
- [ ] Los 2 nodos restantes detectan el timeout (1.5-3s)
- [ ] Uno de ellos se postula como candidato
- [ ] El otro le concede el voto
- [ ] Nuevo lider elegido en < 5 segundos
- [ ] El estado muestra: `-> LIDER (term N+1)`

---

### Test 3.2 — Persistencia de Datos tras Caida

**Objetivo**: Verificar que los datos confirmados NO se pierden al caer un nodo.

1. Con el cluster corriendo, enviar 5 PROPOSEs al lider
2. Esperar 2 segundos (asegurar commit y replicacion)
3. Verificar GET_LOG en los 3 nodos (deben tener las 5 entradas)
4. **Matar el lider**
5. Esperar re-eleccion (~3s)
6. Verificar GET_LOG en los 2 nodos restantes

**Resultado esperado**:
- [ ] Antes de la caida: los 3 nodos tienen 5 entradas
- [ ] Despues de la caida: los 2 nodos restantes mantienen las 5 entradas intactas
- [ ] Se puede enviar un nuevo PROPOSE al nuevo lider
- [ ] GET_LOG muestra ahora 6 entradas en los 2 nodos activos

---

### Test 3.3 — Recuperacion de Nodo

**Objetivo**: Verificar que un nodo que vuelve a subir se sincroniza.

1. Con el cluster de 2 nodos (tras Test 3.2), reiniciar el nodo caido
2. Observar los logs del nodo recuperado

**Resultado esperado**:
- [ ] El nodo recuperado se une como FOLLOWER
- [ ] Recibe heartbeats del lider actual
- [ ] Su log se sincroniza con los demas (recibe las entradas que se perdio)
- [ ] GET_LOG en el nodo recuperado muestra todas las entradas

---

### Test 3.4 — Caida de Follower (no afecta al sistema)

**Objetivo**: Verificar que la caida de un follower no interrumpe el servicio.

1. Con el cluster de 3 nodos corriendo, identificar un FOLLOWER
2. Matar el proceso del follower
3. Enviar PROPOSEs al lider

**Resultado esperado**:
- [ ] El lider sigue aceptando PROPOSEs (quorum 2 de 3 se mantiene)
- [ ] Las entradas se replican al follower restante
- [ ] GET_LOG en el lider y follower activo muestran los mismos datos
- [ ] El Cliente Vigilante sigue funcionando (consulta nodos disponibles)

---

### Test 3.5 — Redireccion de PROPOSE

**Objetivo**: Verificar que un PROPOSE enviado a un follower redirige al lider.

```bash
python3 -c "
import socket, struct

def send_frame(s, data):
    s.sendall(struct.pack('>I', len(data.encode())) + data.encode())

def recv_frame(s):
    raw = b''
    while len(raw) < 4: raw += s.recv(4 - len(raw))
    length = struct.unpack('>I', raw)[0]
    data = b''
    while len(data) < length: data += s.recv(length - len(data))
    return data.decode()

# Enviar PROPOSE a un nodo que NO es lider
# (probar los 3 puertos, uno debe redirigir)
for port in [6001, 6002, 6003]:
    try:
        s = socket.socket()
        s.settimeout(2)
        s.connect(('127.0.0.1', port))
        send_frame(s, 'PROPOSE|cam1|test|2026-07-09_15:00:00|test.bmp')
        reply = recv_frame(s)
        s.close()
        print(f'Puerto {port}: {reply}')
    except:
        print(f'Puerto {port}: no disponible')
"
```

**Resultado esperado**:
- [ ] El lider responde `PROPOSE_OK|<index>`
- [ ] Los followers responden `PROPOSE_REDIRECT|<host>:<port>`
- [ ] El host:port del redirect apunta al lider real

---

## FASE 4: Prueba End-to-End Completa

### Test 4.1 — Pipeline Completo (Sistema Integrado)

**Objetivo**: Verificar el flujo completo desde camara hasta visualizacion.

**Paso 1 — Preparar modelo** (si no se hizo antes):
```bash
cd persona1_java
./build.sh
./run_training.sh
```

**Paso 2 — Levantar servicios** (cada uno en su terminal):
```bash
# Terminal 1: Servidor de pesos
cd persona1_java && ./run_weights_server.sh

# Terminal 2: Nodo Raft Java
cd persona1_java && java -cp out cc4p1.raft.RaftNodeMain java1 config/cluster.properties

# Terminal 3: Nodo Raft Python
cd persona2_python && python3 src/raft/raft_node_main.py python1 config/cluster.properties

# Terminal 4: Nodo Raft C++
cd persona3_cpp && ./build/raft_node cpp1 config/cluster.properties

# Esperar 5 segundos para eleccion de lider

# Terminal 5: Simulador camara 1
cd persona2_python && python3 src/camaras/camara_cliente_movil.py --id 1 --port 7101

# Terminal 6: Simulador camara 2
cd persona2_python && python3 src/camaras/camara_cliente_movil.py --id 2 --port 7102

# Terminal 7: Simulador camara 3
cd persona2_python && python3 src/camaras/camara_cliente_movil.py --id 3 --port 7103

# Terminal 8: Servidor de Testeo
cd persona2_python && python3 src/testeo/main.py config/testing.properties

# Terminal 9: Cliente Vigilante
cd persona3_cpp && ./build/vigilante config/vigilante.properties
```

**Paso 3 — Verificar**:

- [ ] El Servidor de Testeo descarga el modelo desde Java: `Modelo descargado`
- [ ] Las 3 camaras se conectan: `Camara X conectada`
- [ ] Se detectan objetos en cada frame: `Detectado: <clase> (confianza: X%)`
- [ ] Se guardan imagenes BMP: archivos `.bmp` en directorio de detecciones
- [ ] Se proponen eventos al cluster: `PROPOSE aceptado`
- [ ] El cluster replica las entradas: `Aplicado: <clase> (index N)`
- [ ] El Cliente Vigilante muestra las detecciones en http://localhost:8080
- [ ] La tabla se actualiza automaticamente cada 3 segundos
- [ ] Las imagenes se muestran en la columna Foto (si estan accesibles)

---

### Test 4.2 — Tolerancia a Fallos durante Operacion

**Objetivo**: Verificar que el sistema sobrevive a la caida de un nodo durante operacion activa.

Con todo el pipeline del Test 4.1 corriendo:

1. **Esperar** a que se acumulen al menos 10 detecciones
2. **Verificar** el numero en el Vigilante: debe mostrar >= 10
3. **Matar un nodo Raft** (preferiblemente el lider)
4. **Observar**:
   - [ ] Los otros 2 nodos eligen nuevo lider en < 5s
   - [ ] El Servidor de Testeo sigue proponiendo (sigue redirect)
   - [ ] El Vigilante sigue mostrando datos (reconecta a otro nodo)
   - [ ] No se pierden detecciones ya confirmadas
   - [ ] Nuevas detecciones siguen apareciendo en la tabla

---

### Test 4.3 — Multiples Camaras Simultaneas

**Objetivo**: Verificar escalabilidad con 3 camaras enviando frames simultaneamente.

Con el pipeline completo corriendo:

- [ ] Las 3 camaras envian frames concurrentemente
- [ ] El Servidor de Testeo procesa frames de las 3 sin bloqueos
- [ ] Las detecciones registran correctamente la camara de origen (cam1, cam2, cam3)
- [ ] El Vigilante muestra detecciones de las 3 camaras mezcladas cronologicamente

---

## FASE 5: Pruebas de Red (LAN/WIFI)

### Test 5.1 — Despliegue en Red Local

**Objetivo**: Verificar funcionamiento en red real (no solo localhost).

**Configuracion**: 2-3 maquinas en la misma red LAN/WIFI.

1. Obtener IPs de cada maquina: `ip addr` o `ipconfig`
2. Editar `cluster.properties` en los 3 proyectos con IPs reales:
   ```properties
   cluster.nodes=java1,python1,cpp1
   java1.host=192.168.1.X
   java1.port=6001
   python1.host=192.168.1.Y
   python1.port=6002
   cpp1.host=192.168.1.Z
   cpp1.port=6003
   ```
3. Abrir puertos en firewall si es necesario:
   ```bash
   # Linux (iptables)
   sudo iptables -A INPUT -p tcp --dport 6001 -j ACCEPT
   sudo iptables -A INPUT -p tcp --dport 6003 -j ACCEPT
   sudo iptables -A INPUT -p tcp --dport 6100 -j ACCEPT
   sudo iptables -A INPUT -p tcp --dport 8080 -j ACCEPT

   # Windows (PowerShell como admin)
   New-NetFirewallRule -DisplayName "Raft Python" -Direction Inbound -Protocol TCP -LocalPort 6002 -Action Allow
   ```
4. Ejecutar cada componente en su maquina asignada
5. Acceder al Vigilante desde cualquier maquina: `http://192.168.1.Z:8080`

**Resultado esperado**:
- [ ] Los 3 nodos se descubren por red y eligen lider
- [ ] El Servidor de Testeo descarga el modelo desde otra maquina
- [ ] Las camaras se conectan al Servidor de Testeo por red
- [ ] El Vigilante es accesible desde cualquier maquina en la red
- [ ] La latencia de consenso es < 1 segundo en LAN

---

### Test 5.2 — Sistemas Operativos Distintos (SO1 != SO2)

**Objetivo**: Verificar que el sistema funciona con Ubuntu y Windows simultaneamente.

| Maquina | SO | Componentes |
|---|---|---|
| Maquina A (Ubuntu) | SO1 | Nodo Raft Java + Servidor Entrenamiento/Pesos |
| Maquina B (Windows) | SO2 | Nodo Raft Python + Servidor Testeo + Camaras |
| Maquina C (Ubuntu) | SO1 | Nodo Raft C++ + Cliente Vigilante |

**Resultado esperado**:
- [ ] Cluster Raft funciona con nodos en diferentes SO
- [ ] El modelo se transfiere de Ubuntu a Windows correctamente
- [ ] El protocolo de wire es compatible entre SO (endianness, encoding)

---

## Checklist Final de Evaluacion

### Requisitos del enunciado

- [ ] Entrenamiento con n >= 4 clases (persona, perro, gato, carro)
- [ ] Minimo 3 camaras IP funcionando simultaneamente
- [ ] Servidor de Testeo detecta objetos y guarda imagen + registro
- [ ] Cliente Vigilante muestra: foto, tipo, fecha/hora, camara
- [ ] Algoritmo Raft implementado y funcional (eleccion, replicacion, commit)
- [ ] Tolerancia a fallos: 1 nodo cae, los demas siguen funcionando
- [ ] 3 lenguajes de programacion: Java, Python, C++
- [ ] SO1 != SO2 (Ubuntu y Windows)
- [ ] Solo sockets crudos (sin frameworks, websockets, RabbitMQ, MQ)
- [ ] Hilos usados correctamente (sin corrupcion de registros)
- [ ] Despliegue en LAN/WIFI sin internet
- [ ] Cero dependencias externas en cada lenguaje

### Artefactos de entrega

- [ ] Codigo fuente de los 3 lenguajes (sin binarios compilados)
- [ ] PDF Informe
- [ ] PDF Presentacion
- [ ] Diagrama de arquitectura
- [ ] Diagrama de protocolo
