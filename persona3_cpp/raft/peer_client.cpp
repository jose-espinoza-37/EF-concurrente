#include "peer_client.h"
#include "../common/frame.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <cstring>
#include <cerrno>

namespace cc4p1 {

std::optional<std::string> PeerClient::send(const std::string& host, int port,
                                            const std::string& message,
                                            int connectTimeoutMs,
                                            int readTimeoutMs) {
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) return std::nullopt;

    int flags = fcntl(sockfd, F_GETFL, 0);
    fcntl(sockfd, F_SETFL, flags | O_NONBLOCK);

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(static_cast<uint16_t>(port));
    inet_pton(AF_INET, host.c_str(), &addr.sin_addr);

    int ret = connect(sockfd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        close(sockfd);
        return std::nullopt;
    }

    if (ret < 0) {
        struct pollfd pfd{};
        pfd.fd     = sockfd;
        pfd.events = POLLOUT;
        ret = poll(&pfd, 1, connectTimeoutMs);
        if (ret <= 0) { close(sockfd); return std::nullopt; }

        int err = 0;
        socklen_t len = sizeof(err);
        getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &err, &len);
        if (err != 0) { close(sockfd); return std::nullopt; }
    }

    fcntl(sockfd, F_SETFL, flags);

    struct timeval tv;
    tv.tv_sec  = readTimeoutMs / 1000;
    tv.tv_usec = (readTimeoutMs % 1000) * 1000;
    setsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    if (!Frame::write(sockfd, message)) {
        close(sockfd);
        return std::nullopt;
    }

    std::string reply;
    if (!Frame::read(sockfd, reply)) {
        close(sockfd);
        return std::nullopt;
    }

    close(sockfd);
    return reply;
}

}
