package cc4p1.raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class StateMachine {

    private final List<LogEntry> encounters = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public void apply(LogEntry entry) {
        lock.writeLock().lock();
        try {
            encounters.add(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<LogEntry> snapshot() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(encounters));
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return encounters.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
