package cc4p1.raft;

/** Los 3 estados posibles de un nodo segun el algoritmo Raft. */
public enum RaftState {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
