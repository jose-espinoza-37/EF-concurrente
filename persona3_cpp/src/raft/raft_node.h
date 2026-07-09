#pragma once
#include "raft_config.h"
#include "log_entry.h"
#include "state_machine.h"
#include "raft_server.h"
#include <mutex>
#include <atomic>
#include <thread>
#include <string>
#include <vector>
#include <map>
#include <random>
#include <chrono>
#include <memory>

namespace cc4p1 {

enum class RaftState { FOLLOWER, CANDIDATE, LEADER };

class RaftNode {
public:
    explicit RaftNode(const RaftConfig& config);
    ~RaftNode();

    void start();
    void stop();

    std::string handleMessage(const std::string& message);

    RaftState  getState() const;
    int        getCurrentTerm() const;
    std::string getCurrentLeader() const;
    int        getLogSize() const;
    int        getCommitIndex() const;

private:
    RaftConfig config_;

    int currentTerm_ = 0;
    std::string votedFor_;
    std::vector<LogEntry> log_;

    int commitIndex_ = 0;
    int lastApplied_ = 0;
    RaftState state_ = RaftState::FOLLOWER;
    std::string currentLeaderId_;

    std::map<std::string, int> nextIndex_;
    std::map<std::string, int> matchIndex_;

    StateMachine stateMachine_;
    std::unique_ptr<RaftServer> server_;

    mutable std::recursive_mutex mutex_;
    std::atomic<bool> running_{false};

    std::thread electionThread_;
    std::thread heartbeatThread_;
    std::chrono::steady_clock::time_point lastHeartbeat_;
    int electionTimeoutMs_ = 2000;
    std::mt19937 rng_;

    void resetElectionTimeout();
    void electionTimerLoop();
    void heartbeatLoop();

    void becomeFollower(int term);
    void becomeCandidate();
    void becomeLeader();

    std::string handleRequestVote(const std::string& msg);
    std::string handleAppendEntries(const std::string& msg);
    std::string handlePropose(const std::string& msg);
    std::string handleGetLog();

    void replicateToAllPeers();
    void recalculateCommitIndex();
    void applyCommitted();

    int lastLogIndex() const;
    int lastLogTerm() const;
    int majority() const;
};

}
