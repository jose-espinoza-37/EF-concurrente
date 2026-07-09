#pragma once
#include <string>
#include <vector>
#include <sstream>

namespace cc4p1 {

struct LogEntry {
    int index = 0;
    int term = 0;
    std::string camera;
    std::string clase;
    std::string timestamp;
    std::string imagePath;

    std::string encode() const {
        return std::to_string(index) + "," + std::to_string(term) + "," +
               camera + "," + clase + "," + timestamp + "," + imagePath;
    }

    static LogEntry decode(const std::string& s) {
        LogEntry e;
        std::istringstream ss(s);
        std::string tok;
        std::getline(ss, tok, ','); e.index = std::stoi(tok);
        std::getline(ss, tok, ','); e.term  = std::stoi(tok);
        std::getline(ss, tok, ','); e.camera = tok;
        std::getline(ss, tok, ','); e.clase = tok;
        std::getline(ss, tok, ','); e.timestamp = tok;
        std::getline(ss, tok, ','); e.imagePath = tok;
        return e;
    }

    static std::string encodeList(const std::vector<LogEntry>& entries) {
        std::string result;
        for (size_t i = 0; i < entries.size(); i++) {
            if (i > 0) result += "~";
            result += entries[i].encode();
        }
        return result;
    }

    static std::vector<LogEntry> decodeList(const std::string& s) {
        std::vector<LogEntry> entries;
        if (s.empty()) return entries;
        std::istringstream ss(s);
        std::string tok;
        while (std::getline(ss, tok, '~')) {
            if (!tok.empty()) entries.push_back(decode(tok));
        }
        return entries;
    }
};

}
