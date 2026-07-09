#include "vigilante_config.h"
#include "raft_log_client.h"
#include "http_server.h"
#include <iostream>
#include <csignal>
#include <atomic>
#include <thread>

static std::atomic<bool> running{true};

void signalHandler(int) { running = false; }

int main(int argc, char* argv[]) {
    if (argc < 2) {
        std::cerr << "Uso: " << argv[0] << " <vigilante.properties>" << std::endl;
        return 1;
    }

    signal(SIGINT,  signalHandler);
    signal(SIGTERM, signalHandler);

    auto config = cc4p1::VigilanteConfig::load(argv[1]);

    cc4p1::RaftLogClient logClient(config.clusterNodes, config.refreshIntervalMs);
    logClient.start();

    cc4p1::HttpServer httpServer(config.httpPort, logClient, config.detectionsDir);
    httpServer.start();

    std::cout << "[Vigilante] Cliente Vigilante iniciado" << std::endl;
    std::cout << "[Vigilante] Conectado a " << config.clusterNodes.size()
              << " nodos del cluster" << std::endl;
    std::cout << "[Vigilante] Abrir http://localhost:" << config.httpPort
              << " en el navegador" << std::endl;

    while (running) {
        std::this_thread::sleep_for(std::chrono::seconds(10));
        auto entries = logClient.getEntries();
        std::cout << "[Vigilante] Detecciones: " << entries.size() << std::endl;
    }

    httpServer.stop();
    logClient.stop();
    std::cout << "[Vigilante] Detenido." << std::endl;
    return 0;
}
