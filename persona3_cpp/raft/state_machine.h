#pragma once
#include "log_entry.h"
#include <vector>
#include <shared_mutex>

namespace cc4p1 {

class StateMachine {
public:
    void apply(const LogEntry& entry);
    std::vector<LogEntry> snapshot() const;
    int size() const;

private:
    mutable std::shared_mutex mutex_;
    std::vector<LogEntry> entries_;
};

}
