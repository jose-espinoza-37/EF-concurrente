#pragma once
#include <string>
#include <functional>
#include <atomic>
#include <thread>

namespace cc4p1 {

class RaftServer {
public:
    using Handler = std::function<std::string(const std::string&)>;

    RaftServer(const std::string& host, int port, Handler handler);
    ~RaftServer();

    void start();
    void stop();

private:
    std::string host_;
    int port_;
    Handler handler_;
    int serverFd_ = -1;
    std::atomic<bool> running_{false};
    std::thread acceptThread_;

    void acceptLoop();
    void handleConnection(int clientFd);
};

}
