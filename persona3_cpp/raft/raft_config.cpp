#include "raft_config.h"
#include "../common/utils.h"
#include <stdexcept>

namespace cc4p1 {

RaftConfig RaftConfig::load(const std::string& selfId, const std::string& path) {
    auto props = loadProperties(path);
    RaftConfig config;
    config.selfId = selfId;

    auto nodeIds = split(props["cluster.nodes"], ',');
    for (auto& id : nodeIds) {
        id = trim(id);
        NodeInfo node;
        node.id   = id;
        node.host = props["node." + id + ".host"];
        node.port = std::stoi(props["node." + id + ".port"]);
        config.allNodes[id] = node;

        if (id == selfId)
            config.selfNode = node;
        else
            config.peers.push_back(node);
    }

    if (config.selfNode.id.empty())
        throw std::runtime_error("Nodo '" + selfId + "' no encontrado en config del cluster");

    return config;
}

}
