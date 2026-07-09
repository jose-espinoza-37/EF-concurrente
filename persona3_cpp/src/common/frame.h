#pragma once
#include <string>

namespace cc4p1 {

class Frame {
public:
    static bool write(int sockfd, const std::string& payload);
    static bool read(int sockfd, std::string& payload);

private:
    static bool sendAll(int sockfd, const char* data, size_t len);
    static bool recvAll(int sockfd, char* data, size_t len);
};

}
