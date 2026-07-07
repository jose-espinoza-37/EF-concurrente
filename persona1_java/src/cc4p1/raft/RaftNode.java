package cc4p1.raft;

import cc4p1.common.ProtocolMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementacion del nodo Raft. Es agnostica al lenguaje de los peers: solo
 * conoce el protocolo de texto de {@link ProtocolMessage} y habla con ellos
 * via {@link PeerClient}, por eso puede convivir en el mismo cluster con
 * nodos escritos en Python y C++.
 *
 * Concurrencia:
 *  - Un {@link ReentrantLock} protege todo el estado mutable compartido
 *    (term, log, commitIndex, etc.) para evitar corrupcion cuando varios
 *    hilos (el aceptador de conexiones, el timer de eleccion y el heartbeat)
 *    lo tocan al mismo tiempo.
 *  - Un {@link ExecutorService} envia los RPC salientes (RequestVote /
 *    AppendEntries) a todos los peers EN PARALELO, para que un peer lento o
 *    caido no bloquee a los demas.
 */
public class RaftNode {

    private static final long ELECTION_TIMEOUT_MIN_MS = 1500;
    private static final long ELECTION_TIMEOUT_MAX_MS = 3000;
    private static final long HEARTBEAT_INTERVAL_MS = 500;

    private final RaftConfig config;
    private final StateMachine stateMachine;
    private final Random random = new Random();

    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    // Pool "cached": crece segun haga falta para contactar a todos los peers
    // en paralelo (RequestVote/AppendEntries), y libera hilos ociosos solo.
    private final ExecutorService rpcPool = Executors.newCachedThreadPool();

    // --- Estado persistente (en esta version simplificada, solo en memoria) ---
    private long currentTerm = 0;
    private String votedFor = null;
    private final List<LogEntry> log = new ArrayList<>();

    // --- Estado de volatil en todos los nodos ---
    private long commitIndex = 0;
    private long lastApplied = 0;
    private RaftState state = RaftState.FOLLOWER;
    private String currentLeaderId = null;

    // --- Estado de volatil solo en el lider ---
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();

    private ScheduledFuture<?> electionTask;
    private ScheduledFuture<?> heartbeatTask;

    public RaftNode(RaftConfig config, StateMachine stateMachine) {
        this.config = config;
        this.stateMachine = stateMachine;
    }

    /** Arranca el nodo como FOLLOWER y programa el primer timeout de eleccion. */
    public void start() {
        System.out.println("[" + config.selfId + "] Iniciando como FOLLOWER. Peers: " + peerIds());
        resetElectionTimer();
    }

    private String peerIds() {
        StringBuilder sb = new StringBuilder();
        for (RaftConfig.PeerInfo p : config.peers) sb.append(p.id).append(" ");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Timers
    // ------------------------------------------------------------------

    private void resetElectionTimer() {
        if (electionTask != null) {
            electionTask.cancel(false);
        }
        long delay = ELECTION_TIMEOUT_MIN_MS
                + (long) (random.nextDouble() * (ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS));
        electionTask = timers.schedule(this::onElectionTimeout, delay, TimeUnit.MILLISECONDS);
    }

    private void onElectionTimeout() {
        lock.lock();
        try {
            if (state == RaftState.LEADER) {
                return; // el lider no compite consigo mismo
            }
        } finally {
            lock.unlock();
        }
        becomeCandidate();
    }

    // ------------------------------------------------------------------
    // Transiciones de estado
    // ------------------------------------------------------------------

    private void becomeCandidate() {
        long termForElection;
        String selfId = config.selfId;
        long lastLogIndex;
        long lastLogTerm;

        lock.lock();
        try {
            state = RaftState.CANDIDATE;
            currentTerm++;
            votedFor = selfId;
            termForElection = currentTerm;
            lastLogIndex = log.isEmpty() ? 0 : log.get(log.size() - 1).index;
            lastLogTerm = log.isEmpty() ? 0 : log.get(log.size() - 1).term;
        } finally {
            lock.unlock();
        }

        resetElectionTimer();
        System.out.println("[" + selfId + "] Timeout -> me postulo a LIDER (term " + termForElection + ")");

        int votesNeeded = (config.peers.size() + 1) / 2 + 1; // mayoria incluyendome
        List<Future<Boolean>> futures = new ArrayList<>();

        for (RaftConfig.PeerInfo peer : config.peers) {
            String msg = ProtocolMessage.REQUEST_VOTE + "|" + termForElection + "|" + selfId
                    + "|" + lastLogIndex + "|" + lastLogTerm;
            futures.add(rpcPool.submit(() -> {
                String reply = PeerClient.send(peer.host, peer.port, msg);
                if (reply == null) return false;
                String[] f = ProtocolMessage.split(reply);
                if (!f[0].equals(ProtocolMessage.REQUEST_VOTE_REPLY)) return false;
                long replyTerm = Long.parseLong(f[1]);
                boolean granted = f[2].equals("1");
                if (replyTerm > termForElection) {
                    becomeFollower(replyTerm);
                    return false;
                }
                return granted;
            }));
        }

        int votes = 1; // mi propio voto
        for (Future<Boolean> f : futures) {
            try {
                if (f.get(READ_TIMEOUT_SAFE_MS(), TimeUnit.MILLISECONDS)) votes++;
            } catch (Exception ignored) {
                // peer no respondio a tiempo: se cuenta como voto negativo
            }
        }

        lock.lock();
        try {
            boolean stillCandidate = state == RaftState.CANDIDATE && currentTerm == termForElection;
            if (stillCandidate && votes >= votesNeeded) {
                doBecomeLeader();
            }
        } finally {
            lock.unlock();
        }
    }

    private static long READ_TIMEOUT_SAFE_MS() {
        return 800;
    }

    /** Debe llamarse con el lock ya tomado. */
    private void doBecomeLeader() {
        state = RaftState.LEADER;
        currentLeaderId = config.selfId;
        long lastIndex = log.isEmpty() ? 0 : log.get(log.size() - 1).index;
        nextIndex.clear();
        matchIndex.clear();
        for (RaftConfig.PeerInfo peer : config.peers) {
            nextIndex.put(peer.id, lastIndex + 1);
            matchIndex.put(peer.id, 0L);
        }
        System.out.println("[" + config.selfId + "] Elegido LIDER en el term " + currentTerm);
        if (electionTask != null) electionTask.cancel(false);
        startHeartbeatLoop();
    }

    private void becomeFollower(long newTerm) {
        lock.lock();
        try {
            currentTerm = newTerm;
            state = RaftState.FOLLOWER;
            votedFor = null;
        } finally {
            lock.unlock();
        }
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        resetElectionTimer();
    }

    private void startHeartbeatLoop() {
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        heartbeatTask = timers.scheduleAtFixedRate(this::replicateToAllPeers,
                0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // Replicacion de log (llamado periodicamente como heartbeat, y tambien
    // inmediatamente despues de aceptar una entrada nueva del cliente)
    // ------------------------------------------------------------------

    private void replicateToAllPeers() {
        lock.lock();
        boolean amLeader = state == RaftState.LEADER;
        long term = currentTerm;
        lock.unlock();
        if (!amLeader) return;

        for (RaftConfig.PeerInfo peer : config.peers) {
            rpcPool.submit(() -> replicateToPeer(peer, term));
        }
    }

    private void replicateToPeer(RaftConfig.PeerInfo peer, long term) {
        long prevLogIndex;
        long prevLogTerm;
        long leaderCommit;
        List<LogEntry> entriesToSend = new ArrayList<>();

        lock.lock();
        try {
            if (state != RaftState.LEADER || currentTerm != term) return;
            long ni = nextIndex.getOrDefault(peer.id, 1L);
            prevLogIndex = ni - 1;
            prevLogTerm = (prevLogIndex > 0 && prevLogIndex <= log.size()) ? log.get((int) prevLogIndex - 1).term : 0;
            for (int i = (int) ni - 1; i < log.size(); i++) {
                entriesToSend.add(log.get(i));
            }
            leaderCommit = commitIndex;
        } finally {
            lock.unlock();
        }

        StringBuilder entriesEncoded = new StringBuilder();
        for (int i = 0; i < entriesToSend.size(); i++) {
            if (i > 0) entriesEncoded.append(ProtocolMessage.ENTRY_SEP);
            entriesEncoded.append(entriesToSend.get(i).encode());
        }

        String msg = ProtocolMessage.APPEND_ENTRIES + "|" + term + "|" + config.selfId + "|"
                + prevLogIndex + "|" + prevLogTerm + "|" + leaderCommit + "|" + entriesEncoded;

        String reply = PeerClient.send(peer.host, peer.port, msg);
        if (reply == null) return; // peer caido: se reintentara en el siguiente heartbeat

        String[] f = ProtocolMessage.split(reply);
        if (!f[0].equals(ProtocolMessage.APPEND_ENTRIES_REPLY)) return;
        long replyTerm = Long.parseLong(f[1]);
        boolean success = f[2].equals("1");

        lock.lock();
        try {
            if (replyTerm > currentTerm) {
                lock.unlock();
                becomeFollower(replyTerm);
                return;
            }
            if (state != RaftState.LEADER) return;
            if (success) {
                long newMatch = prevLogIndex + entriesToSend.size();
                matchIndex.put(peer.id, newMatch);
                nextIndex.put(peer.id, newMatch + 1);
                recalculateCommitIndexLocked();
            } else {
                // Conflicto: retrocedemos nextIndex y probamos de nuevo en el proximo ciclo
                long ni = nextIndex.getOrDefault(peer.id, 1L);
                nextIndex.put(peer.id, Math.max(1, ni - 1));
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /** Debe llamarse con el lock tomado. Avanza commitIndex si hay mayoria replicando un indice. */
    private void recalculateCommitIndexLocked() {
        long lastIndex = log.isEmpty() ? 0 : log.get(log.size() - 1).index;
        for (long idx = lastIndex; idx > commitIndex; idx--) {
            int count = 1; // el lider ya lo tiene
            for (Long m : matchIndex.values()) {
                if (m >= idx) count++;
            }
            int majority = (config.peers.size() + 1) / 2 + 1;
            LogEntry entry = log.get((int) idx - 1);
            if (count >= majority && entry.term == currentTerm) {
                commitIndex = idx;
                break;
            }
        }
        applyCommittedLocked();
    }

    private void applyCommittedLocked() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = log.get((int) lastApplied - 1);
            stateMachine.apply(entry);
        }
    }

    // ------------------------------------------------------------------
    // Manejo de RPCs entrantes (llamados desde RaftServer, en el hilo de
    // esa conexion -- por eso todo aqui va protegido por el lock)
    // ------------------------------------------------------------------

    /** RV|term|candidateId|lastLogIndex|lastLogTerm -> RVR|term|granted */
    public String handleRequestVote(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
        lock.lock();
        try {
            if (term > currentTerm) {
                currentTerm = term;
                state = RaftState.FOLLOWER;
                votedFor = null;
            }
            boolean logOk = isCandidateLogUpToDateLocked(lastLogIndex, lastLogTerm);
            boolean granted = term >= currentTerm
                    && (votedFor == null || votedFor.equals(candidateId))
                    && logOk;
            if (granted) {
                votedFor = candidateId;
                resetElectionTimer();
            }
            return ProtocolMessage.REQUEST_VOTE_REPLY + "|" + currentTerm + "|" + (granted ? "1" : "0");
        } finally {
            lock.unlock();
        }
    }

    private boolean isCandidateLogUpToDateLocked(long lastLogIndex, long lastLogTerm) {
        long myLastTerm = log.isEmpty() ? 0 : log.get(log.size() - 1).term;
        long myLastIndex = log.isEmpty() ? 0 : log.get(log.size() - 1).index;
        if (lastLogTerm != myLastTerm) return lastLogTerm > myLastTerm;
        return lastLogIndex >= myLastIndex;
    }

    /** AE|term|leaderId|prevLogIndex|prevLogTerm|leaderCommit|entries -> AER|term|success|matchIndex */
    public String handleAppendEntries(long term, String leaderId, long prevLogIndex, long prevLogTerm,
                                       long leaderCommit, String entriesRaw) {
        lock.lock();
        try {
            if (term < currentTerm) {
                return ProtocolMessage.APPEND_ENTRIES_REPLY + "|" + currentTerm + "|0|0";
            }
            currentTerm = term;
            state = RaftState.FOLLOWER;
            currentLeaderId = leaderId;
            resetElectionTimer();
            if (heartbeatTask != null) heartbeatTask.cancel(false);

            if (prevLogIndex > 0) {
                if (prevLogIndex > log.size() || log.get((int) prevLogIndex - 1).term != prevLogTerm) {
                    return ProtocolMessage.APPEND_ENTRIES_REPLY + "|" + currentTerm + "|0|0";
                }
            }

            if (!entriesRaw.isEmpty()) {
                String[] rawEntries = entriesRaw.split(ProtocolMessage.ENTRY_SEP);
                int insertPos = (int) prevLogIndex; // 0-based en la lista
                for (String rawEntry : rawEntries) {
                    LogEntry entry = LogEntry.decode(rawEntry);
                    if (insertPos < log.size()) {
                        if (log.get(insertPos).term != entry.term) {
                            while (log.size() > insertPos) log.remove(log.size() - 1);
                            log.add(entry);
                        }
                    } else {
                        log.add(entry);
                    }
                    insertPos++;
                }
            }

            if (leaderCommit > commitIndex) {
                long lastNewIndex = log.isEmpty() ? 0 : log.get(log.size() - 1).index;
                commitIndex = Math.min(leaderCommit, lastNewIndex);
                applyCommittedLocked();
            }

            long matchIdx = log.isEmpty() ? 0 : log.get(log.size() - 1).index;
            return ProtocolMessage.APPEND_ENTRIES_REPLY + "|" + currentTerm + "|1|" + matchIdx;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Llamado por RaftServer cuando llega un PROPOSE (nueva deteccion) del
     * Servidor de Testeo. Solo el lider puede aceptarlo.
     * Devuelve el mensaje de respuesta ya codificado (PROPOSE_OK o PROPOSE_REDIRECT).
     */
    public String handlePropose(int camera, String clase, String timestamp, String imagePath) {
        lock.lock();
        boolean amLeader = state == RaftState.LEADER;
        String leaderId = currentLeaderId;
        LogEntry newEntry = null;
        try {
            if (amLeader) {
                long newIndex = (log.isEmpty() ? 0 : log.get(log.size() - 1).index) + 1;
                newEntry = new LogEntry(newIndex, currentTerm, camera, clase, timestamp, imagePath);
                log.add(newEntry);
            }
        } finally {
            lock.unlock();
        }

        if (!amLeader) {
            RaftConfig.PeerInfo leaderInfo = leaderId == null ? null : config.allNodes.get(leaderId);
            if (leaderInfo == null) {
                return ProtocolMessage.PROPOSE_REDIRECT + "|NONE|0";
            }
            return ProtocolMessage.PROPOSE_REDIRECT + "|" + leaderInfo.host + "|" + leaderInfo.port;
        }

        // Soy el lider: replico inmediatamente (no espero al proximo heartbeat)
        replicateToAllPeers();
        return ProtocolMessage.PROPOSE_OK + "|" + newEntry.index;
    }

    /** GET_LOG -> LOG_DATA|count|entry1~entry2~... */
    public String handleGetLog() {
        List<LogEntry> snapshot = stateMachine.snapshot();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) sb.append(ProtocolMessage.ENTRY_SEP);
            sb.append(snapshot.get(i).encode());
        }
        return ProtocolMessage.LOG_DATA + "|" + snapshot.size() + "|" + sb;
    }

    public boolean isLeader() {
        lock.lock();
        try {
            return state == RaftState.LEADER;
        } finally {
            lock.unlock();
        }
    }

    public String describeStatus() {
        lock.lock();
        try {
            return "[" + config.selfId + "] estado=" + state + " term=" + currentTerm
                    + " logSize=" + log.size() + " commitIndex=" + commitIndex
                    + " encuentros_aplicados=" + stateMachine.size();
        } finally {
            lock.unlock();
        }
    }
}
