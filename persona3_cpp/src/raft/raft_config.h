#pragma once
#include <string>
#include <vector>
#include <map>

namespace cc4p1 {

struct NodeInfo {
    std::string id;
    std::string host;
    int port = 0;
};

struct RaftConfig {
    std::string selfId;
    NodeInfo selfNode;
    std::vector<NodeInfo> peers;
    std::map<std::string, NodeInfo> allNodes;

    static RaftConfig load(const std::string& selfId, const std::string& path);
};

}
