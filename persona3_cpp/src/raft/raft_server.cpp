#include "raft_server.h"
#include "../common/frame.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <iostream>
#include <thread>

namespace cc4p1 {

RaftServer::RaftServer(const std::string& host, int port, Handler handler)
    : host_(host), port_(port), handler_(std::move(handler)) {}

RaftServer::~RaftServer() {
    stop();
}

void RaftServer::start() {
    serverFd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (serverFd_ < 0) {
        std::cerr << "[RaftServer] Error creando socket: " << strerror(errno) << std::endl;
        return;
    }

    int opt = 1;
    setsockopt(serverFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(static_cast<uint16_t>(port_));
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(serverFd_, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::cerr << "[RaftServer] Error bind puerto " << port_ << ": " << strerror(errno) << std::endl;
        return;
    }

    listen(serverFd_, 20);
    running_ = true;
    acceptThread_ = std::thread(&RaftServer::acceptLoop, this);
    std::cout << "[RaftServer] Escuchando en " << host_ << ":" << port_ << std::endl;
}

void RaftServer::stop() {
    running_ = false;
    if (serverFd_ >= 0) {
        shutdown(serverFd_, SHUT_RDWR);
        close(serverFd_);
        serverFd_ = -1;
    }
    if (acceptThread_.joinable()) acceptThread_.join();
}

void RaftServer::acceptLoop() {
    while (running_) {
        struct sockaddr_in clientAddr{};
        socklen_t len = sizeof(clientAddr);
        int clientFd = accept(serverFd_, reinterpret_cast<struct sockaddr*>(&clientAddr), &len);
        if (clientFd < 0) {
            if (running_) continue;
            break;
        }
        std::thread(&RaftServer::handleConnection, this, clientFd).detach();
    }
}

void RaftServer::handleConnection(int clientFd) {
    struct timeval tv;
    tv.tv_sec  = 2;
    tv.tv_usec = 0;
    setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    std::string request;
    if (Frame::read(clientFd, request)) {
        std::string response = handler_(request);
        if (!response.empty()) {
            Frame::write(clientFd, response);
        }
    }
    close(clientFd);
}

}
