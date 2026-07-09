#pragma once
#include <string>
#include <vector>
#include "../raft/raft_config.h"

namespace cc4p1 {

struct VigilanteConfig {
    std::vector<NodeInfo> clusterNodes;
    int httpPort          = 8080;
    int refreshIntervalMs = 3000;
    std::string detectionsDir = "detecciones";

    static VigilanteConfig load(const std::string& path);
};

}
