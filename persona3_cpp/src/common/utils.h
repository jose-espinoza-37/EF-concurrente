#pragma once
#include <string>
#include <vector>
#include <sstream>
#include <fstream>
#include <map>

namespace cc4p1 {

inline std::vector<std::string> split(const std::string& s, char delim) {
    std::vector<std::string> tokens;
    std::istringstream stream(s);
    std::string token;
    while (std::getline(stream, token, delim)) {
        tokens.push_back(token);
    }
    return tokens;
}

inline std::string trim(const std::string& s) {
    auto start = s.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    auto end = s.find_last_not_of(" \t\r\n");
    return s.substr(start, end - start + 1);
}

inline std::map<std::string, std::string> loadProperties(const std::string& path) {
    std::map<std::string, std::string> props;
    std::ifstream file(path);
    std::string line;
    while (std::getline(file, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        props[trim(line.substr(0, eq))] = trim(line.substr(eq + 1));
    }
    return props;
}

}
