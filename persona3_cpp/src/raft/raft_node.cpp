#include "raft_node.h"
#include "peer_client.h"
#include "../common/protocol.h"
#include "../common/utils.h"
#include <iostream>
#include <sstream>
#include <future>
#include <algorithm>

namespace cc4p1 {

RaftNode::RaftNode(const RaftConfig& config)
    : config_(config), rng_(std::random_device{}())
{
    resetElectionTimeout();
    lastHeartbeat_ = std::chrono::steady_clock::now();
}

RaftNode::~RaftNode() { stop(); }

void RaftNode::start() {
    running_ = true;

    server_ = std::make_unique<RaftServer>(
        config_.selfNode.host, config_.selfNode.port,
        [this](const std::string& msg) { return handleMessage(msg); }
    );
    server_->start();

    electionThread_  = std::thread(&RaftNode::electionTimerLoop, this);
    heartbeatThread_ = std::thread(&RaftNode::heartbeatLoop, this);

    std::cout << "[" << config_.selfId << "] Nodo Raft iniciado (term "
              << currentTerm_ << ")" << std::endl;
}

void RaftNode::stop() {
    running_ = false;
    if (server_) server_->stop();
    if (electionThread_.joinable())  electionThread_.join();
    if (heartbeatThread_.joinable()) heartbeatThread_.join();
}

// ── Accessors ───────────────────────────────────────────────

RaftState RaftNode::getState() const {
    std::lock_guard lock(mutex_);
    return state_;
}

int RaftNode::getCurrentTerm() const {
    std::lock_guard lock(mutex_);
    return currentTerm_;
}

std::string RaftNode::getCurrentLeader() const {
    std::lock_guard lock(mutex_);
    return currentLeaderId_;
}

int RaftNode::getLogSize() const {
    std::lock_guard lock(mutex_);
    return static_cast<int>(log_.size());
}

int RaftNode::getCommitIndex() const {
    std::lock_guard lock(mutex_);
    return commitIndex_;
}

// ── Helpers ─────────────────────────────────────────────────

void RaftNode::resetElectionTimeout() {
    std::uniform_int_distribution<int> dist(1500, 3000);
    electionTimeoutMs_ = dist(rng_);
    lastHeartbeat_ = std::chrono::steady_clock::now();
}

int RaftNode::lastLogIndex() const {
    return log_.empty() ? 0 : log_.back().index;
}

int RaftNode::lastLogTerm() const {
    return log_.empty() ? 0 : log_.back().term;
}

int RaftNode::majority() const {
    return (static_cast<int>(config_.peers.size()) + 1) / 2 + 1;
}

// ── Timer Loops ─────────────────────────────────────────────

void RaftNode::electionTimerLoop() {
    while (running_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        std::lock_guard lock(mutex_);
        if (state_ == RaftState::LEADER) continue;

        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - lastHeartbeat_).count();

        if (elapsed >= electionTimeoutMs_) {
            becomeCandidate();
        }
    }
}

void RaftNode::heartbeatLoop() {
    while (running_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));

        std::lock_guard lock(mutex_);
        if (state_ == RaftState::LEADER) {
            replicateToAllPeers();
        }
    }
}

// ── State Transitions ───────────────────────────────────────

void RaftNode::becomeFollower(int term) {
    state_       = RaftState::FOLLOWER;
    currentTerm_ = term;
    votedFor_.clear();
    resetElectionTimeout();
    std::cout << "[" << config_.selfId << "] -> FOLLOWER (term " << term << ")" << std::endl;
}

void RaftNode::becomeCandidate() {
    currentTerm_++;
    state_    = RaftState::CANDIDATE;
    votedFor_ = config_.selfId;
    resetElectionTimeout();

    int term    = currentTerm_;
    int lastIdx = lastLogIndex();
    int lastTrm = lastLogTerm();

    std::cout << "[" << config_.selfId << "] Timeout -> me postulo a LIDER (term "
              << term << ")" << std::endl;

    std::string rvMsg = std::string(Protocol::REQUEST_VOTE) + "|" +
                        std::to_string(term) + "|" + config_.selfId + "|" +
                        std::to_string(lastIdx) + "|" + std::to_string(lastTrm);

    int votes = 1;

    std::vector<std::future<std::optional<std::string>>> futures;
    for (auto& peer : config_.peers) {
        auto h = peer.host;
        auto p = peer.port;
        futures.push_back(std::async(std::launch::async, [h, p, rvMsg]() {
            return PeerClient::send(h, p, rvMsg, 300, 800);
        }));
    }

    for (auto& f : futures) {
        auto result = f.get();
        if (!result) continue;

        auto parts = split(*result, '|');
        if (parts.size() >= 3 && parts[0] == Protocol::REQUEST_VOTE_REPLY) {
            int replyTerm = std::stoi(parts[1]);
            bool granted  = (parts[2] == "true");

            if (replyTerm > currentTerm_) {
                becomeFollower(replyTerm);
                return;
            }
            if (granted) votes++;
        }
    }

    if (state_ == RaftState::CANDIDATE && votes >= majority()) {
        becomeLeader();
    }
}

void RaftNode::becomeLeader() {
    state_           = RaftState::LEADER;
    currentLeaderId_ = config_.selfId;

    int nextIdx = lastLogIndex() + 1;
    for (auto& peer : config_.peers) {
        nextIndex_[peer.id]  = nextIdx;
        matchIndex_[peer.id] = 0;
    }

    std::cout << "[" << config_.selfId << "] -> LIDER (term " << currentTerm_ << ")" << std::endl;
    replicateToAllPeers();
}

// ── Message Dispatcher ──────────────────────────────────────

std::string RaftNode::handleMessage(const std::string& message) {
    std::lock_guard lock(mutex_);

    auto sepPos = message.find('|');
    std::string type = (sepPos != std::string::npos) ? message.substr(0, sepPos) : message;

    if (type == Protocol::REQUEST_VOTE)   return handleRequestVote(message);
    if (type == Protocol::APPEND_ENTRIES) return handleAppendEntries(message);
    if (type == Protocol::PROPOSE)        return handlePropose(message);
    if (type == Protocol::GET_LOG)        return handleGetLog();

    return "";
}

// ── RequestVote Handler ─────────────────────────────────────

std::string RaftNode::handleRequestVote(const std::string& msg) {
    auto parts = split(msg, '|');
    int term             = std::stoi(parts[1]);
    std::string candidId = parts[2];
    int candLastIdx      = std::stoi(parts[3]);
    int candLastTrm      = std::stoi(parts[4]);

    if (term > currentTerm_) becomeFollower(term);

    bool granted = false;
    if (term >= currentTerm_) {
        if (votedFor_.empty() || votedFor_ == candidId) {
            if (candLastTrm > lastLogTerm() ||
                (candLastTrm == lastLogTerm() && candLastIdx >= lastLogIndex())) {
                granted  = true;
                votedFor_ = candidId;
                resetElectionTimeout();
                std::cout << "[" << config_.selfId << "] Voto concedido a "
                          << candidId << " (term " << term << ")" << std::endl;
            }
        }
    }

    return std::string(Protocol::REQUEST_VOTE_REPLY) + "|" +
           std::to_string(currentTerm_) + "|" + (granted ? "true" : "false");
}

// ── AppendEntries Handler ───────────────────────────────────

std::string RaftNode::handleAppendEntries(const std::string& msg) {
    auto parts = split(msg, '|');
    int term             = std::stoi(parts[1]);
    std::string leaderId = parts[2];
    int prevLogIndex     = std::stoi(parts[3]);
    int prevLogTerm      = std::stoi(parts[4]);
    int leaderCommit     = std::stoi(parts[5]);
    std::string entriesStr = (parts.size() > 6) ? parts[6] : "";

    if (term < currentTerm_) {
        return std::string(Protocol::APPEND_ENTRIES_REPLY) + "|" +
               std::to_string(currentTerm_) + "|false|0";
    }

    if (term > currentTerm_ || state_ != RaftState::FOLLOWER) {
        becomeFollower(term);
    }

    currentLeaderId_ = leaderId;
    resetElectionTimeout();

    if (prevLogIndex > 0) {
        if (prevLogIndex > static_cast<int>(log_.size())) {
            return std::string(Protocol::APPEND_ENTRIES_REPLY) + "|" +
                   std::to_string(currentTerm_) + "|false|0";
        }
        if (log_[prevLogIndex - 1].term != prevLogTerm) {
            log_.resize(static_cast<size_t>(prevLogIndex - 1));
            return std::string(Protocol::APPEND_ENTRIES_REPLY) + "|" +
                   std::to_string(currentTerm_) + "|false|0";
        }
    }

    if (!entriesStr.empty()) {
        auto newEntries = LogEntry::decodeList(entriesStr);
        for (auto& entry : newEntries) {
            int idx = entry.index;
            if (idx <= static_cast<int>(log_.size())) {
                if (log_[idx - 1].term != entry.term) {
                    log_.resize(static_cast<size_t>(idx - 1));
                    log_.push_back(entry);
                }
            } else {
                log_.push_back(entry);
            }
        }
    }

    if (leaderCommit > commitIndex_) {
        commitIndex_ = std::min(leaderCommit, static_cast<int>(log_.size()));
        applyCommitted();
    }

    int matchIdx = static_cast<int>(log_.size());
    return std::string(Protocol::APPEND_ENTRIES_REPLY) + "|" +
           std::to_string(currentTerm_) + "|true|" + std::to_string(matchIdx);
}

// ── Propose Handler ─────────────────────────────────────────

std::string RaftNode::handlePropose(const std::string& msg) {
    if (state_ != RaftState::LEADER) {
        if (!currentLeaderId_.empty() && config_.allNodes.count(currentLeaderId_)) {
            auto& leader = config_.allNodes.at(currentLeaderId_);
            return std::string(Protocol::PROPOSE_REDIRECT) + "|" +
                   leader.host + ":" + std::to_string(leader.port);
        }
        return std::string(Protocol::PROPOSE_REDIRECT) + "|";
    }

    auto parts = split(msg, '|');
    LogEntry entry;
    entry.index     = lastLogIndex() + 1;
    entry.term      = currentTerm_;
    entry.camera    = (parts.size() > 1) ? parts[1] : "";
    entry.clase     = (parts.size() > 2) ? parts[2] : "";
    entry.timestamp = (parts.size() > 3) ? parts[3] : "";
    entry.imagePath = (parts.size() > 4) ? parts[4] : "";

    log_.push_back(entry);

    std::cout << "[" << config_.selfId << "] PROPOSE aceptado: " << entry.clase
              << " de " << entry.camera << " (index " << entry.index << ")" << std::endl;

    replicateToAllPeers();

    return std::string(Protocol::PROPOSE_OK) + "|" + std::to_string(entry.index);
}

// ── GetLog Handler ──────────────────────────────────────────

std::string RaftNode::handleGetLog() {
    auto entries = stateMachine_.snapshot();
    return std::string(Protocol::LOG_DATA) + "|" + LogEntry::encodeList(entries);
}

// ── Replication ─────────────────────────────────────────────

void RaftNode::replicateToAllPeers() {
    struct PeerMsg {
        std::string peerId;
        std::string host;
        int port;
        std::string message;
    };

    std::vector<PeerMsg> msgs;
    for (auto& peer : config_.peers) {
        PeerMsg pm;
        pm.peerId = peer.id;
        pm.host   = peer.host;
        pm.port   = peer.port;

        int nextIdx = nextIndex_[peer.id];
        int prevIdx = nextIdx - 1;
        int prevTrm = 0;
        if (prevIdx > 0 && prevIdx <= static_cast<int>(log_.size()))
            prevTrm = log_[prevIdx - 1].term;

        std::vector<LogEntry> entries;
        for (int i = nextIdx; i <= static_cast<int>(log_.size()); i++)
            entries.push_back(log_[i - 1]);

        pm.message = std::string(Protocol::APPEND_ENTRIES) + "|" +
                     std::to_string(currentTerm_) + "|" + config_.selfId + "|" +
                     std::to_string(prevIdx) + "|" + std::to_string(prevTrm) + "|" +
                     std::to_string(commitIndex_) + "|" +
                     LogEntry::encodeList(entries);
        msgs.push_back(std::move(pm));
    }

    struct Reply {
        std::string peerId;
        std::optional<std::string> response;
    };

    std::vector<std::future<Reply>> futures;
    for (auto& m : msgs) {
        auto pid = m.peerId;
        auto h   = m.host;
        auto p   = m.port;
        auto msg = m.message;
        futures.push_back(std::async(std::launch::async, [pid, h, p, msg]() -> Reply {
            return {pid, PeerClient::send(h, p, msg)};
        }));
    }

    for (auto& f : futures) {
        auto reply = f.get();
        if (!reply.response) continue;

        auto parts = split(*reply.response, '|');
        if (parts.size() >= 3 && parts[0] == Protocol::APPEND_ENTRIES_REPLY) {
            int replyTerm = std::stoi(parts[1]);
            bool success  = (parts[2] == "true");

            if (replyTerm > currentTerm_) {
                becomeFollower(replyTerm);
                return;
            }

            if (success && parts.size() > 3) {
                int matchIdx = std::stoi(parts[3]);
                matchIndex_[reply.peerId] = matchIdx;
                nextIndex_[reply.peerId]  = matchIdx + 1;
            } else if (!success) {
                if (nextIndex_[reply.peerId] > 1)
                    nextIndex_[reply.peerId]--;
            }
        }
    }

    recalculateCommitIndex();
}

void RaftNode::recalculateCommitIndex() {
    for (int n = static_cast<int>(log_.size()); n > commitIndex_; n--) {
        if (log_[n - 1].term != currentTerm_) continue;

        int count = 1;
        for (auto& peer : config_.peers) {
            if (matchIndex_[peer.id] >= n) count++;
        }

        if (count >= majority()) {
            commitIndex_ = n;
            applyCommitted();
            break;
        }
    }
}

void RaftNode::applyCommitted() {
    while (lastApplied_ < commitIndex_) {
        lastApplied_++;
        stateMachine_.apply(log_[lastApplied_ - 1]);
        std::cout << "[" << config_.selfId << "] Aplicado: "
                  << log_[lastApplied_ - 1].clase
                  << " (index " << lastApplied_ << ")" << std::endl;
    }
}

}
