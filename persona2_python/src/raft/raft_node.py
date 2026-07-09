

import random
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Optional

from common import protocol
from raft import peer_client
from raft.log_entry import LogEntry
from raft.raft_config import RaftConfig
from raft.state_machine import StateMachine

FOLLOWER = "FOLLOWER"
CANDIDATE = "CANDIDATE"
LEADER = "LEADER"

ELECTION_TIMEOUT_MIN_S = 1.5
ELECTION_TIMEOUT_MAX_S = 3.0
HEARTBEAT_INTERVAL_S = 0.5
RPC_WAIT_TIMEOUT_S = 0.8


class RaftNode:
    def __init__(self, config: RaftConfig, state_machine: StateMachine):
        self.config = config
        self.state_machine = state_machine

        self._lock = threading.RLock()
        self._rpc_pool = ThreadPoolExecutor(max_workers=max(4, len(config.peers) * 2))

        # --- estado (protegido por _lock) ---
        self.current_term = 0
        self.voted_for: Optional[str] = None
        self.log: List[LogEntry] = []
        self.commit_index = 0
        self.last_applied = 0
        self.state = FOLLOWER
        self.current_leader_id: Optional[str] = None
        self.next_index: Dict[str, int] = {}
        self.match_index: Dict[str, int] = {}

        self._election_timer: Optional[threading.Timer] = None
        self._heartbeat_thread: Optional[threading.Thread] = None
        self._stopped = False

    # ------------------------------------------------------------------
    # Arranque
    # ------------------------------------------------------------------

    def start(self):
        peer_ids = " ".join(p.node_id for p in self.config.peers)
        print("[%s] Iniciando como FOLLOWER. Peers: %s" % (self.config.self_id, peer_ids))
        self._reset_election_timer()

    def stop(self):
        self._stopped = True
        with self._lock:
            if self._election_timer:
                self._election_timer.cancel()
        self._rpc_pool.shutdown(wait=False)

    # ------------------------------------------------------------------
    # Timers
    # ------------------------------------------------------------------

    def _reset_election_timer(self):
        with self._lock:
            if self._election_timer:
                self._election_timer.cancel()
            delay = random.uniform(ELECTION_TIMEOUT_MIN_S, ELECTION_TIMEOUT_MAX_S)
            self._election_timer = threading.Timer(delay, self._on_election_timeout)
            self._election_timer.daemon = True
            self._election_timer.start()

    def _on_election_timeout(self):
        if self._stopped:
            return
        with self._lock:
            if self.state == LEADER:
                return
        self._become_candidate()

    # ------------------------------------------------------------------
    # Transiciones de estado
    # ------------------------------------------------------------------

    def _become_candidate(self):
        with self._lock:
            self.state = CANDIDATE
            self.current_term += 1
            self.voted_for = self.config.self_id
            term_for_election = self.current_term
            last_log_index = self.log[-1].index if self.log else 0
            last_log_term = self.log[-1].term if self.log else 0

        self._reset_election_timer()
        print("[%s] Timeout -> me postulo a LIDER (term %d)" % (self.config.self_id, term_for_election))

        votes_needed = (len(self.config.peers) + 1) // 2 + 1
        futures = []
        for peer in self.config.peers:
            msg = "%s|%d|%s|%d|%d" % (
                protocol.REQUEST_VOTE, term_for_election, self.config.self_id,
                last_log_index, last_log_term,
            )
            futures.append(self._rpc_pool.submit(self._send_request_vote, peer, msg, term_for_election))

        votes = 1  # mi propio voto
        for f in futures:
            try:
                if f.result(timeout=RPC_WAIT_TIMEOUT_S):
                    votes += 1
            except Exception:
                pass  # peer no respondio a tiempo -> se cuenta como voto negativo

        with self._lock:
            still_candidate = self.state == CANDIDATE and self.current_term == term_for_election
            if still_candidate and votes >= votes_needed:
                self._do_become_leader_locked()

    def _send_request_vote(self, peer, msg, term_for_election) -> bool:
        reply = peer_client.send(peer.host, peer.port, msg)
        if reply is None:
            return False
        f = protocol.split(reply)
        if f[0] != protocol.REQUEST_VOTE_REPLY:
            return False
        reply_term = int(f[1])
        granted = f[2] == "1"
        if reply_term > term_for_election:
            self._become_follower(reply_term)
            return False
        return granted

    def _do_become_leader_locked(self):
        """Debe llamarse con el lock ya tomado."""
        self.state = LEADER
        self.current_leader_id = self.config.self_id
        last_index = self.log[-1].index if self.log else 0
        self.next_index = {p.node_id: last_index + 1 for p in self.config.peers}
        self.match_index = {p.node_id: 0 for p in self.config.peers}
        print("[%s] Elegido LIDER en el term %d" % (self.config.self_id, self.current_term))
        if self._election_timer:
            self._election_timer.cancel()
        self._start_heartbeat_loop()

    def _become_follower(self, new_term: int):
        with self._lock:
            self.current_term = new_term
            self.state = FOLLOWER
            self.voted_for = None
        self._reset_election_timer()

    def _start_heartbeat_loop(self):
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            return  # ya hay un bucle de heartbeat corriendo

        def loop():
            while not self._stopped:
                with self._lock:
                    if self.state != LEADER:
                        return
                self._replicate_to_all_peers()
                time.sleep(HEARTBEAT_INTERVAL_S)

        self._heartbeat_thread = threading.Thread(
            target=loop, name="heartbeat-%s" % self.config.self_id, daemon=True
        )
        self._heartbeat_thread.start()

    # ------------------------------------------------------------------
    # Replicacion de log
    # ------------------------------------------------------------------

    def _replicate_to_all_peers(self):
        with self._lock:
            if self.state != LEADER:
                return
            term = self.current_term
        for peer in self.config.peers:
            self._rpc_pool.submit(self._replicate_to_peer, peer, term)

    def _replicate_to_peer(self, peer, term):
        with self._lock:
            if self.state != LEADER or self.current_term != term:
                return
            ni = self.next_index.get(peer.node_id, 1)
            prev_log_index = ni - 1
            prev_log_term = self.log[prev_log_index - 1].term if 0 < prev_log_index <= len(self.log) else 0
            entries_to_send = self.log[ni - 1:]
            leader_commit = self.commit_index

        entries_encoded = protocol.ENTRY_SEP.join(e.encode() for e in entries_to_send)
        msg = "%s|%d|%s|%d|%d|%d|%s" % (
            protocol.APPEND_ENTRIES, term, self.config.self_id,
            prev_log_index, prev_log_term, leader_commit, entries_encoded,
        )
        reply = peer_client.send(peer.host, peer.port, msg)
        if reply is None:
            return  # peer caido: se reintentara en el siguiente heartbeat

        f = protocol.split(reply)
        if f[0] != protocol.APPEND_ENTRIES_REPLY:
            return
        reply_term = int(f[1])
        success = f[2] == "1"

        with self._lock:
            if reply_term > self.current_term:
                should_step_down = True
            else:
                should_step_down = False
                if self.state == LEADER:
                    if success:
                        new_match = prev_log_index + len(entries_to_send)
                        self.match_index[peer.node_id] = new_match
                        self.next_index[peer.node_id] = new_match + 1
                        self._recalculate_commit_index_locked()
                    else:
                        ni2 = self.next_index.get(peer.node_id, 1)
                        self.next_index[peer.node_id] = max(1, ni2 - 1)

        if should_step_down:
            self._become_follower(reply_term)

    def _recalculate_commit_index_locked(self):
        """Debe llamarse con el lock tomado. Avanza commit_index si hay mayoria."""
        last_index = self.log[-1].index if self.log else 0
        idx = last_index
        majority = (len(self.config.peers) + 1) // 2 + 1
        while idx > self.commit_index:
            count = 1  # el lider ya la tiene
            for m in self.match_index.values():
                if m >= idx:
                    count += 1
            entry = self.log[idx - 1]
            if count >= majority and entry.term == self.current_term:
                self.commit_index = idx
                break
            idx -= 1
        self._apply_committed_locked()

    def _apply_committed_locked(self):
        while self.last_applied < self.commit_index:
            self.last_applied += 1
            entry = self.log[self.last_applied - 1]
            self.state_machine.apply(entry)

    # ------------------------------------------------------------------
    # Manejo de RPCs entrantes (llamados desde RaftServer)
    # ------------------------------------------------------------------

    def handle_request_vote(self, term: int, candidate_id: str, last_log_index: int, last_log_term: int) -> str:
        with self._lock:
            if term > self.current_term:
                self.current_term = term
                self.state = FOLLOWER
                self.voted_for = None
            log_ok = self._is_candidate_log_up_to_date_locked(last_log_index, last_log_term)
            granted = (
                term >= self.current_term
                and (self.voted_for is None or self.voted_for == candidate_id)
                and log_ok
            )
            if granted:
                self.voted_for = candidate_id
                self._reset_election_timer()
            return "%s|%d|%s" % (protocol.REQUEST_VOTE_REPLY, self.current_term, "1" if granted else "0")

    def _is_candidate_log_up_to_date_locked(self, last_log_index: int, last_log_term: int) -> bool:
        my_last_term = self.log[-1].term if self.log else 0
        my_last_index = self.log[-1].index if self.log else 0
        if last_log_term != my_last_term:
            return last_log_term > my_last_term
        return last_log_index >= my_last_index

    def handle_append_entries(self, term: int, leader_id: str, prev_log_index: int,
                               prev_log_term: int, leader_commit: int, entries_raw: str) -> str:
        with self._lock:
            if term < self.current_term:
                return "%s|%d|0|0" % (protocol.APPEND_ENTRIES_REPLY, self.current_term)

            self.current_term = term
            self.state = FOLLOWER
            self.current_leader_id = leader_id
            self._reset_election_timer()

            if prev_log_index > 0:
                if prev_log_index > len(self.log) or self.log[prev_log_index - 1].term != prev_log_term:
                    return "%s|%d|0|0" % (protocol.APPEND_ENTRIES_REPLY, self.current_term)

            if entries_raw:
                raw_entries = entries_raw.split(protocol.ENTRY_SEP)
                insert_pos = prev_log_index  # 0-based en la lista
                for raw_entry in raw_entries:
                    entry = LogEntry.decode(raw_entry)
                    if insert_pos < len(self.log):
                        if self.log[insert_pos].term != entry.term:
                            del self.log[insert_pos:]
                            self.log.append(entry)
                    else:
                        self.log.append(entry)
                    insert_pos += 1

            if leader_commit > self.commit_index:
                last_new_index = self.log[-1].index if self.log else 0
                self.commit_index = min(leader_commit, last_new_index)
                self._apply_committed_locked()

            match_idx = self.log[-1].index if self.log else 0
            return "%s|%d|1|%d" % (protocol.APPEND_ENTRIES_REPLY, self.current_term, match_idx)

    def handle_propose(self, camera: int, clase: str, timestamp: str, image_path: str) -> str:
        new_entry = None
        with self._lock:
            am_leader = self.state == LEADER
            leader_id = self.current_leader_id
            if am_leader:
                new_index = (self.log[-1].index if self.log else 0) + 1
                new_entry = LogEntry(new_index, self.current_term, camera, clase, timestamp, image_path)
                self.log.append(new_entry)

        if not am_leader:
            leader_info = self.config.all_nodes.get(leader_id) if leader_id else None
            if leader_info is None:
                return "%s|NONE|0" % protocol.PROPOSE_REDIRECT
            return "%s|%s|%d" % (protocol.PROPOSE_REDIRECT, leader_info.host, leader_info.port)

        self._replicate_to_all_peers()
        return "%s|%d" % (protocol.PROPOSE_OK, new_entry.index)

    def handle_get_log(self) -> str:
        snapshot = self.state_machine.snapshot()
        encoded = protocol.ENTRY_SEP.join(e.encode() for e in snapshot)
        return "%s|%d|%s" % (protocol.LOG_DATA, len(snapshot), encoded)

    def is_leader(self) -> bool:
        with self._lock:
            return self.state == LEADER

    def describe_status(self) -> str:
        with self._lock:
            return "[%s] estado=%s term=%d logSize=%d commitIndex=%d encuentros_aplicados=%d" % (
                self.config.self_id, self.state, self.current_term,
                len(self.log), self.commit_index, self.state_machine.size(),
            )
