#include "frame.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <cstring>

namespace cc4p1 {

bool Frame::sendAll(int sockfd, const char* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = ::send(sockfd, data + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool Frame::recvAll(int sockfd, char* data, size_t len) {
    size_t received = 0;
    while (received < len) {
        ssize_t n = ::recv(sockfd, data + received, len - received, 0);
        if (n <= 0) return false;
        received += static_cast<size_t>(n);
    }
    return true;
}

bool Frame::write(int sockfd, const std::string& payload) {
    uint32_t len = htonl(static_cast<uint32_t>(payload.size()));
    if (!sendAll(sockfd, reinterpret_cast<const char*>(&len), 4)) return false;
    if (!payload.empty()) {
        if (!sendAll(sockfd, payload.c_str(), payload.size())) return false;
    }
    return true;
}

bool Frame::read(int sockfd, std::string& payload) {
    uint32_t len;
    if (!recvAll(sockfd, reinterpret_cast<char*>(&len), 4)) return false;
    len = ntohl(len);
    if (len == 0) {
        payload.clear();
        return true;
    }
    if (len > 10 * 1024 * 1024) return false;
    payload.resize(len);
    return recvAll(sockfd, &payload[0], len);
}

}
