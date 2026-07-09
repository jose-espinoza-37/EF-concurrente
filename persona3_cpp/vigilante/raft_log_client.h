#pragma once
#include "../raft/log_entry.h"
#include "../raft/raft_config.h"
#include <vector>
#include <mutex>
#include <atomic>
#include <thread>

namespace cc4p1 {

class RaftLogClient {
public:
    RaftLogClient(const std::vector<NodeInfo>& nodes, int intervalMs);
    ~RaftLogClient();

    void start();
    void stop();
    std::vector<LogEntry> getEntries() const;

private:
    std::vector<NodeInfo> nodes_;
    int intervalMs_;

    mutable std::mutex mutex_;
    std::vector<LogEntry> entries_;
    std::atomic<bool> running_{false};
    std::thread pollThread_;

    void pollLoop();
};

}
