#include "raft_node.h"
#include <iostream>
#include <csignal>
#include <atomic>
#include <thread>

static std::atomic<bool> running{true};

void signalHandler(int) { running = false; }

int main(int argc, char* argv[]) {
    if (argc < 3) {
        std::cerr << "Uso: " << argv[0] << " <nodeId> <cluster.properties>" << std::endl;
        return 1;
    }

    std::string nodeId     = argv[1];
    std::string configPath = argv[2];

    signal(SIGINT,  signalHandler);
    signal(SIGTERM, signalHandler);

    auto config = cc4p1::RaftConfig::load(nodeId, configPath);
    cc4p1::RaftNode node(config);
    node.start();

    while (running) {
        std::this_thread::sleep_for(std::chrono::seconds(5));

        auto state = node.getState();
        const char* stateStr = "FOLLOWER";
        if (state == cc4p1::RaftState::CANDIDATE) stateStr = "CANDIDATE";
        if (state == cc4p1::RaftState::LEADER)    stateStr = "LEADER";

        std::cout << "[" << nodeId << "] Estado: " << stateStr
                  << " | Term: "   << node.getCurrentTerm()
                  << " | Lider: "  << node.getCurrentLeader()
                  << " | Log: "    << node.getLogSize()
                  << " | Commit: " << node.getCommitIndex()
                  << std::endl;
    }

    node.stop();
    std::cout << "[" << nodeId << "] Nodo detenido." << std::endl;
    return 0;
}
