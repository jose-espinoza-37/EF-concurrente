# Persona 3 - C++ (LP3)

Nodo Raft #3 y Cliente Vigilante para el sistema distribuido de reconocimiento de objetos.

## Componentes

1. **Nodo Raft (cpp1)** - Tercer nodo del cluster de consenso heterogeneo (Java + Python + C++)
2. **Cliente Vigilante** - Interfaz web que muestra el registro de detecciones en tiempo real

## Requisitos

- g++ con soporte C++17
- Linux (POSIX sockets)

## Compilar

```bash
chmod +x build.sh
./build.sh
```

## Ejecutar

### Nodo Raft (en cluster con Java y Python)
```bash
./run_raft_node.sh
```

### Cliente Vigilante
```bash
./run_vigilante.sh
# Abrir http://localhost:8080 en el navegador
```

### Test local (3 nodos C++ + vigilante)
```bash
./run_local_test.sh
```

## Protocolo

Compatible con Java (persona1) y Python (persona2):
- Frame: `[4 bytes big-endian longitud][payload UTF-8]`
- Mensajes Raft: RV, RVR, AE, AER
- Mensajes cliente: PROPOSE, PROPOSE_OK, PROPOSE_REDIRECT, GET_LOG, LOG_DATA

## Puertos

| Servicio | Puerto |
|----------|--------|
| Nodo Raft C++ (cpp1) | 6003 |
| Cliente Vigilante HTTP | 8080 |
