#include "raft_log_client.h"
#include "../common/protocol.h"
#include "../common/utils.h"
#include "../raft/peer_client.h"
#include <iostream>

namespace cc4p1 {

RaftLogClient::RaftLogClient(const std::vector<NodeInfo>& nodes, int intervalMs)
    : nodes_(nodes), intervalMs_(intervalMs) {}

RaftLogClient::~RaftLogClient() { stop(); }

void RaftLogClient::start() {
    running_ = true;
    pollThread_ = std::thread(&RaftLogClient::pollLoop, this);
}

void RaftLogClient::stop() {
    running_ = false;
    if (pollThread_.joinable()) pollThread_.join();
}

std::vector<LogEntry> RaftLogClient::getEntries() const {
    std::lock_guard lock(mutex_);
    return entries_;
}

void RaftLogClient::pollLoop() {
    while (running_) {
        for (auto& node : nodes_) {
            auto result = PeerClient::send(node.host, node.port,
                                           Protocol::GET_LOG, 500, 1000);
            if (!result) continue;

            auto sepPos = result->find('|');
            if (sepPos == std::string::npos) continue;

            std::string type = result->substr(0, sepPos);
            if (type == Protocol::LOG_DATA) {
                std::string data = result->substr(sepPos + 1);
                auto newEntries = LogEntry::decodeList(data);
                {
                    std::lock_guard lock(mutex_);
                    entries_ = std::move(newEntries);
                }
                break;
            }
        }

        for (int i = 0; i < intervalMs_ / 100 && running_; i++)
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

}
