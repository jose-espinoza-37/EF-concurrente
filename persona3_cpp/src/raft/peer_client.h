#pragma once
#include <string>
#include <optional>

namespace cc4p1 {

class PeerClient {
public:
    static std::optional<std::string> send(const std::string& host, int port,
                                           const std::string& message,
                                           int connectTimeoutMs = 300,
                                           int readTimeoutMs = 500);
};

}
