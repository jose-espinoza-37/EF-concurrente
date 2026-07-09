#pragma once
#include "raft_log_client.h"
#include <string>
#include <atomic>
#include <thread>

namespace cc4p1 {

class HttpServer {
public:
    HttpServer(int port, RaftLogClient& logClient, const std::string& detectionsDir);
    ~HttpServer();

    void start();
    void stop();

private:
    int port_;
    RaftLogClient& logClient_;
    std::string detectionsDir_;
    int serverFd_ = -1;
    std::atomic<bool> running_{false};
    std::thread acceptThread_;

    void acceptLoop();
    void handleConnection(int clientFd);

    void sendHtmlResponse(int fd);
    void sendImageResponse(int fd, const std::string& path);
    void send404(int fd);

    std::string buildHtmlPage();
    static std::string escapeHtml(const std::string& s);
    static std::string urlDecode(const std::string& s);
};

}
