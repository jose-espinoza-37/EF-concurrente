#include "http_server.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <cstring>
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>

namespace cc4p1 {

HttpServer::HttpServer(int port, RaftLogClient& logClient, const std::string& detectionsDir)
    : port_(port), logClient_(logClient), detectionsDir_(detectionsDir) {}

HttpServer::~HttpServer() { stop(); }

void HttpServer::start() {
    serverFd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (serverFd_ < 0) {
        std::cerr << "[HttpServer] Error creando socket" << std::endl;
        return;
    }

    int opt = 1;
    setsockopt(serverFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(static_cast<uint16_t>(port_));
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(serverFd_, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::cerr << "[HttpServer] Error bind puerto " << port_ << ": " << strerror(errno) << std::endl;
        return;
    }

    listen(serverFd_, 20);
    running_ = true;
    acceptThread_ = std::thread(&HttpServer::acceptLoop, this);
    std::cout << "[Vigilante] Servidor HTTP en http://localhost:" << port_ << std::endl;
}

void HttpServer::stop() {
    running_ = false;
    if (serverFd_ >= 0) {
        shutdown(serverFd_, SHUT_RDWR);
        close(serverFd_);
        serverFd_ = -1;
    }
    if (acceptThread_.joinable()) acceptThread_.join();
}

void HttpServer::acceptLoop() {
    while (running_) {
        struct sockaddr_in clientAddr{};
        socklen_t len = sizeof(clientAddr);
        int clientFd = accept(serverFd_, reinterpret_cast<struct sockaddr*>(&clientAddr), &len);
        if (clientFd < 0) {
            if (running_) continue;
            break;
        }
        std::thread(&HttpServer::handleConnection, this, clientFd).detach();
    }
}

void HttpServer::handleConnection(int clientFd) {
    struct timeval tv;
    tv.tv_sec  = 5;
    tv.tv_usec = 0;
    setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    char buffer[4096];
    ssize_t n = recv(clientFd, buffer, sizeof(buffer) - 1, 0);
    if (n <= 0) { close(clientFd); return; }
    buffer[n] = '\0';

    std::string request(buffer);
    std::istringstream iss(request);
    std::string method, path;
    iss >> method >> path;

    if (path == "/" || path == "/index.html") {
        sendHtmlResponse(clientFd);
    } else if (path.substr(0, 5) == "/img/") {
        std::string imgPath = urlDecode(path.substr(5));
        if (imgPath.find("..") != std::string::npos) {
            send404(clientFd);
        } else {
            sendImageResponse(clientFd, imgPath);
        }
    } else {
        send404(clientFd);
    }

    close(clientFd);
}

std::string HttpServer::escapeHtml(const std::string& s) {
    std::string r;
    r.reserve(s.size());
    for (char c : s) {
        switch (c) {
            case '<': r += "&lt;";   break;
            case '>': r += "&gt;";   break;
            case '&': r += "&amp;";  break;
            case '"': r += "&quot;"; break;
            default:  r += c;        break;
        }
    }
    return r;
}

std::string HttpServer::urlDecode(const std::string& s) {
    std::string r;
    for (size_t i = 0; i < s.size(); i++) {
        if (s[i] == '%' && i + 2 < s.size()) {
            int val = 0;
            std::istringstream iss(s.substr(i + 1, 2));
            iss >> std::hex >> val;
            r += static_cast<char>(val);
            i += 2;
        } else if (s[i] == '+') {
            r += ' ';
        } else {
            r += s[i];
        }
    }
    return r;
}

std::string HttpServer::buildHtmlPage() {
    auto entries = logClient_.getEntries();

    std::ostringstream html;
    html << R"(<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta http-equiv="refresh" content="3">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Cliente Vigilante - Sistema de Deteccion</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',Tahoma,sans-serif;background:#1a1a2e;color:#eee;padding:20px}
h1{text-align:center;color:#00d4ff;margin-bottom:5px;font-size:1.8em}
.subtitle{text-align:center;color:#888;margin-bottom:20px;font-size:.9em}
.stats{display:flex;justify-content:center;gap:30px;margin-bottom:20px;flex-wrap:wrap}
.stat-box{background:#16213e;padding:15px 25px;border-radius:10px;text-align:center;border:1px solid #0f3460;min-width:120px}
.stat-box .number{font-size:2em;color:#00d4ff;font-weight:bold}
.stat-box .label{font-size:.85em;color:#888}
table{width:100%;border-collapse:collapse;background:#16213e;border-radius:10px;overflow:hidden}
th{background:#0f3460;color:#00d4ff;padding:12px 15px;text-align:left;font-weight:600}
td{padding:10px 15px;border-bottom:1px solid #1a1a2e;vertical-align:middle}
tr:hover{background:#1a2744}
.img-thumb{width:80px;height:60px;object-fit:cover;border-radius:5px;border:2px solid #0f3460;cursor:pointer}
.badge{display:inline-block;padding:4px 12px;border-radius:15px;font-size:.85em;font-weight:600;text-transform:capitalize}
.badge-persona{background:#e94560}
.badge-perro{background:#0f3460;color:#00d4ff}
.badge-gato{background:#533483}
.badge-carro{background:#e97d00}
.badge-default{background:#555}
.no-data{text-align:center;padding:40px;color:#666;font-size:1.2em}
.footer{text-align:center;margin-top:20px;color:#444;font-size:.8em}
.modal{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.85);z-index:100;justify-content:center;align-items:center}
.modal.active{display:flex}
.modal img{max-width:90%;max-height:90%;border-radius:8px;border:3px solid #00d4ff}
</style>
</head>
<body>
<h1>Cliente Vigilante de Objetos</h1>
<p class="subtitle">Sistema Distribuido de Reconocimiento con Consenso Raft - Nodo C++</p>
<div class="stats">
<div class="stat-box"><div class="number">)" << entries.size() << R"(</div><div class="label">Detecciones</div></div>
</div>
)";

    if (entries.empty()) {
        html << R"(<div class="no-data">Sin detecciones registradas. Esperando datos del cluster Raft...</div>)";
    } else {
        html << R"(<table>
<thead><tr><th>#</th><th>Foto</th><th>Tipo</th><th>Camara</th><th>Fecha y Hora</th></tr></thead>
<tbody>
)";
        for (int i = static_cast<int>(entries.size()) - 1; i >= 0; i--) {
            auto& e = entries[i];
            std::string badge = "badge-default";
            if (e.clase == "persona") badge = "badge-persona";
            else if (e.clase == "perro") badge = "badge-perro";
            else if (e.clase == "gato")  badge = "badge-gato";
            else if (e.clase == "carro") badge = "badge-carro";

            html << "<tr>"
                 << "<td>" << e.index << "</td>"
                 << "<td><img class=\"img-thumb\" src=\"/img/"
                 << escapeHtml(e.imagePath)
                 << "\" onclick=\"openModal(this.src)\""
                 << " onerror=\"this.style.display='none'\"></td>"
                 << "<td><span class=\"badge " << badge << "\">"
                 << escapeHtml(e.clase) << "</span></td>"
                 << "<td>" << escapeHtml(e.camera) << "</td>"
                 << "<td>" << escapeHtml(e.timestamp) << "</td>"
                 << "</tr>\n";
        }
        html << "</tbody></table>\n";
    }

    html << R"HTML(
<div class="modal" id="imgModal" onclick="this.classList.remove('active')">
<img id="modalImg" src="">
</div>
<script>
function openModal(src){
  document.getElementById('modalImg').src=src;
  document.getElementById('imgModal').classList.add('active');
}
</script>
<div class="footer">
Auto-refresco cada 3 segundos | Persona 3 - C++ | CC4P1 Programacion Concurrente y Distribuida
</div>
</body></html>)HTML";

    return html.str();
}

void HttpServer::sendHtmlResponse(int fd) {
    std::string body = buildHtmlPage();
    std::ostringstream resp;
    resp << "HTTP/1.1 200 OK\r\n"
         << "Content-Type: text/html; charset=UTF-8\r\n"
         << "Content-Length: " << body.size() << "\r\n"
         << "Connection: close\r\n\r\n"
         << body;
    std::string r = resp.str();
    ::send(fd, r.c_str(), r.size(), MSG_NOSIGNAL);
}

void HttpServer::sendImageResponse(int fd, const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) file.open(detectionsDir_ + "/" + path, std::ios::binary);
    if (!file) { send404(fd); return; }

    file.seekg(0, std::ios::end);
    auto size = static_cast<size_t>(file.tellg());
    file.seekg(0, std::ios::beg);

    std::vector<char> data(size);
    file.read(data.data(), static_cast<std::streamsize>(size));

    std::string ct = "image/bmp";
    if (path.find(".png")  != std::string::npos) ct = "image/png";
    if (path.find(".jpg")  != std::string::npos ||
        path.find(".jpeg") != std::string::npos) ct = "image/jpeg";

    std::ostringstream hdr;
    hdr << "HTTP/1.1 200 OK\r\n"
        << "Content-Type: " << ct << "\r\n"
        << "Content-Length: " << size << "\r\n"
        << "Connection: close\r\n\r\n";
    std::string h = hdr.str();
    ::send(fd, h.c_str(), h.size(), MSG_NOSIGNAL);
    ::send(fd, data.data(), data.size(), MSG_NOSIGNAL);
}

void HttpServer::send404(int fd) {
    std::string body = "<html><body><h1>404 Not Found</h1></body></html>";
    std::ostringstream resp;
    resp << "HTTP/1.1 404 Not Found\r\n"
         << "Content-Type: text/html\r\n"
         << "Content-Length: " << body.size() << "\r\n"
         << "Connection: close\r\n\r\n"
         << body;
    std::string r = resp.str();
    ::send(fd, r.c_str(), r.size(), MSG_NOSIGNAL);
}

}
