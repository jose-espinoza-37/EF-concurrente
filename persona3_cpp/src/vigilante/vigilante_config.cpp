#include "vigilante_config.h"
#include "../common/utils.h"

namespace cc4p1 {

VigilanteConfig VigilanteConfig::load(const std::string& path) {
    auto props = loadProperties(path);
    VigilanteConfig config;

    auto nodeIds = split(props["cluster.nodes"], ',');
    for (auto& id : nodeIds) {
        id = trim(id);
        NodeInfo node;
        node.id   = id;
        node.host = props[id + ".host"];
        node.port = std::stoi(props[id + ".port"]);
        config.clusterNodes.push_back(node);
    }

    if (props.count("http.port"))
        config.httpPort = std::stoi(props["http.port"]);
    if (props.count("refresh.interval.ms"))
        config.refreshIntervalMs = std::stoi(props["refresh.interval.ms"]);
    if (props.count("detections.dir"))
        config.detectionsDir = props["detections.dir"];

    return config;
}

}
