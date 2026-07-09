#include "state_machine.h"
#include <mutex>

namespace cc4p1 {

void StateMachine::apply(const LogEntry& entry) {
    std::unique_lock lock(mutex_);
    entries_.push_back(entry);
}

std::vector<LogEntry> StateMachine::snapshot() const {
    std::shared_lock lock(mutex_);
    return entries_;
}

int StateMachine::size() const {
    std::shared_lock lock(mutex_);
    return static_cast<int>(entries_.size());
}

}
