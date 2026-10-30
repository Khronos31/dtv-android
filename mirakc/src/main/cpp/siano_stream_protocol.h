#ifndef MIRAKC_SIANO_STREAM_PROTOCOL_H
#define MIRAKC_SIANO_STREAM_PROTOCOL_H

#include <cstdint>

namespace mirakc::siano_stream {

constexpr char kMagic[4] = {'S', 'I', 'A', '2'};
constexpr std::uint32_t kData = 1;
constexpr std::uint32_t kEnd = 2;
constexpr std::uint32_t kMaxDataBytes = 32U * 1024U;
constexpr std::uint32_t kEndPayloadBytes = 8;

enum class Outcome : std::uint32_t {
    exit = 1,
    signal = 2,
    timeout = 3,
    cancel = 4,
    poll_error = 5,
    broker_io = 6,
};

}  // namespace mirakc::siano_stream

#endif
