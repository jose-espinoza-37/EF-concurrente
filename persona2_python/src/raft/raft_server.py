

import socket
import threading

from common import frame, protocol
from raft.raft_node import RaftNode


class RaftServer:
    def __init__(self, port: int, node: RaftNode):
        self.port = port
        self.node = node
        self._server_socket = None
        self._running = True

    def serve_forever(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("0.0.0.0", self.port))
            server.listen(64)
            self._server_socket = server
            print("[RaftServer] Escuchando en el puerto %d" % self.port)
            while self._running:
                try:
                    conn, _addr = server.accept()
                except OSError:
                    break
                threading.Thread(target=self._handle_connection, args=(conn,), daemon=True).start()

    def stop(self):
        self._running = False
        if self._server_socket:
            try:
                self._server_socket.close()
            except OSError:
                pass

    def _handle_connection(self, conn: socket.socket):
        try:
            with conn:
                request = frame.read_text(conn)
                response = self._dispatch(request)
                frame.write_text(conn, response)
        except (IOError, OSError):
            pass  # conexion cerrada abruptamente por el otro lado: se ignora

    def _dispatch(self, message: str) -> str:
        f = protocol.split(message)
        tipo = f[0]

        if tipo == protocol.REQUEST_VOTE:
            term, candidate_id, last_log_index, last_log_term = int(f[1]), f[2], int(f[3]), int(f[4])
            return self.node.handle_request_vote(term, candidate_id, last_log_index, last_log_term)

        if tipo == protocol.APPEND_ENTRIES:
            term = int(f[1])
            leader_id = f[2]
            prev_log_index = int(f[3])
            prev_log_term = int(f[4])
            leader_commit = int(f[5])
            entries = f[6] if len(f) > 6 else ""
            return self.node.handle_append_entries(term, leader_id, prev_log_index, prev_log_term,
                                                     leader_commit, entries)

        if tipo == protocol.PROPOSE:
            camera = int(f[1])
            clase = f[2]
            timestamp = f[3]
            image_path = f[4]
            return self.node.handle_propose(camera, clase, timestamp, image_path)

        if tipo == protocol.GET_LOG:
            return self.node.handle_get_log()

        return "ERROR|tipo_desconocido"
