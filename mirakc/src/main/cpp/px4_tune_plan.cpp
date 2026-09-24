#include "px4_tune_plan.h"

#include <cerrno>
#include <cstdlib>

namespace px4_adapter {
namespace {

constexpr int kFirstGrChannel = 13;
constexpr int kLastGrChannel = 62;

bool is_q3u4_terrestrial_receiver(int receiver) {
    return receiver == 2 || receiver == 3 || receiver == 6 || receiver == 7;
}

bool is_q3u4_satellite_receiver(int receiver) {
    return receiver == 0 || receiver == 1 || receiver == 4 || receiver == 5;
}

bool is_mlt5_receiver(int receiver) {
    return receiver >= 0 && receiver <= 4;
}

bool parse_decimal(std::string_view text, std::uint32_t maximum, std::uint32_t* value) {
    if (text.empty() || (text.size() > 1 && text.front() == '0')) return false;
    for (const char character : text) {
        if (character < '0' || character > '9') return false;
    }
    std::string owned(text);
    char* end = nullptr;
    errno = 0;
    const unsigned long parsed = std::strtoul(owned.c_str(), &end, 10);
    if (errno != 0 || end != owned.c_str() + owned.size() || parsed > maximum) return false;
    *value = static_cast<std::uint32_t>(parsed);
    return true;
}

bool parse_tsid(std::optional<std::string_view> text, std::uint16_t* value) {
    if (!text.has_value()) return false;
    std::uint32_t parsed = 0;
    if (!parse_decimal(*text, 0xffffU, &parsed) || parsed == 0xffffU) {
        return false;
    }
    *value = static_cast<std::uint16_t>(parsed);
    return true;
}

void set_error(std::string* error, const char* message) {
    if (error != nullptr) *error = message;
}

}  // namespace

bool create_tune_plan(
    int receiver,
    std::string_view channel,
    std::optional<std::string_view> tsid,
    TunePlan* plan,
    std::string* error,
    ReceiverMap receiver_map) {
    if (plan == nullptr) {
        set_error(error, "missing tune plan output");
        return false;
    }
    const bool terrestrial = receiver_map == ReceiverMap::kPxMlt5
        ? is_mlt5_receiver(receiver)
        : is_q3u4_terrestrial_receiver(receiver);
    const bool satellite = receiver_map == ReceiverMap::kPxMlt5
        ? is_mlt5_receiver(receiver)
        : is_q3u4_satellite_receiver(receiver);
    if (!terrestrial && !satellite) {
        set_error(error, receiver_map == ReceiverMap::kPxMlt5
            ? "receiver is outside the PX-MLT5 receiver map"
            : "receiver is outside the PX-Q3U4 receiver map");
        return false;
    }
    if (tsid.has_value() && tsid->empty()) {
        set_error(error, "TSID is empty");
        return false;
    }

    TunePlan result;
    result.receiver = receiver;

    std::uint32_t numeric_channel = 0;
    if (parse_decimal(channel, kLastGrChannel, &numeric_channel) &&
        numeric_channel >= kFirstGrChannel) {
        if (!terrestrial || tsid.has_value()) {
            set_error(error, "GR channel/TSID does not match receiver");
            return false;
        }
        result.system = BroadcastSystem::kIsdbT;
        result.frequency_khz = 395142U + numeric_channel * 6000U;
        *plan = result;
        return true;
    }

    if (channel.size() >= 6 && channel.substr(0, 2) == "BS" && channel[4] == '_') {
        // BSdd_slot: exactly two transponder digits, followed by an underscore
        // and an unpadded decimal slot in the range 0..11.
        if (channel.size() < 6 || channel[2] < '0' || channel[2] > '9' ||
            channel[3] < '0' || channel[3] > '9' || channel[4] != '_') {
            set_error(error, "malformed BS channel");
            return false;
        }
        const std::uint32_t transponder =
            static_cast<std::uint32_t>((channel[2] - '0') * 10 + (channel[3] - '0'));
        std::uint32_t slot = 0;
        if (!parse_decimal(channel.substr(5), 11, &slot) ||
            (transponder & 1U) == 0U || transponder < 1U || transponder > 23U) {
            set_error(error, "BS channel is outside the physical map");
            return false;
        }
        if (!satellite) {
            set_error(error, "BS channel requires a satellite receiver");
            return false;
        }
        if (tsid.has_value()) {
            std::uint16_t stream_id = 0;
            if (!parse_tsid(tsid, &stream_id)) {
                set_error(error, "invalid BS TSID");
                return false;
            }
            result.satellite_selector = SatelliteSelector::kStreamId;
            result.satellite_value = stream_id;
        } else {
            result.satellite_selector = SatelliteSelector::kSlot;
            result.satellite_value = static_cast<std::uint16_t>(slot);
        }
        result.system = BroadcastSystem::kIsdbS;
        result.frequency_khz = 1049480U + ((transponder - 1U) / 2U) * 38360U;
        *plan = result;
        return true;
    }

    if (channel.size() >= 3 && channel.substr(0, 2) == "CS") {
        std::uint32_t cs_channel = 0;
        if (!parse_decimal(channel.substr(2), 24, &cs_channel) ||
            cs_channel < 2U || (cs_channel & 1U) != 0U) {
            set_error(error, "CS channel is outside the physical map");
            return false;
        }
        if (!satellite || tsid.has_value()) {
            set_error(error, "CS channel/TSID does not match receiver");
            return false;
        }
        result.system = BroadcastSystem::kIsdbS;
        result.frequency_khz = 1613000U + ((cs_channel - 2U) / 2U) * 40000U;
        result.satellite_selector = SatelliteSelector::kSlot;
        result.satellite_value = 0;
        *plan = result;
        return true;
    }

    set_error(error, "unknown or malformed PX-Q3U4 channel");
    return false;
}

}  // namespace px4_adapter
