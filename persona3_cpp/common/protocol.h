#pragma once

namespace cc4p1 {

struct Protocol {
    static constexpr const char* REQUEST_VOTE         = "RV";
    static constexpr const char* REQUEST_VOTE_REPLY   = "RVR";
    static constexpr const char* APPEND_ENTRIES       = "AE";
    static constexpr const char* APPEND_ENTRIES_REPLY = "AER";

    static constexpr const char* PROPOSE              = "PROPOSE";
    static constexpr const char* PROPOSE_OK           = "PROPOSE_OK";
    static constexpr const char* PROPOSE_REDIRECT     = "PROPOSE_REDIRECT";
    static constexpr const char* GET_LOG              = "GET_LOG";
    static constexpr const char* LOG_DATA             = "LOG_DATA";

    static constexpr const char* GET_WEIGHTS          = "GET_WEIGHTS";
    static constexpr const char* WEIGHTS_META         = "WEIGHTS_META";
};

}
