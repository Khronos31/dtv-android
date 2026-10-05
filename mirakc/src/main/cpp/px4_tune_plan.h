#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace px4_adapter {

enum class BroadcastSystem {
    kIsdbT,
    kIsdbS,
};

enum class SatelliteSelector {
    kNone,
    kSlot,
    kStreamId,
};

enum class ReceiverMap {
    kPxQ3u4,
    kPxW3u4,
    kPxMlt5,
    kPxW3pe4,
    kPxW3pe5,
    kPxQ3pe4,
    kPxQ3pe5,
    kPxMlt8pe3,
    kPxMlt8pe5,
    kDtv02a4tsP,
    kPxM1ur,
    kPxS1ur,
    kDtv03a1tu,
    kDtv021t1sU,
    kDtv02a1t1sU,
};

constexpr bool is_q3_fixed_profile(ReceiverMap model) {
    return model == ReceiverMap::kPxQ3u4 || model == ReceiverMap::kPxQ3pe4 ||
        model == ReceiverMap::kPxQ3pe5;
}

constexpr bool is_w3_fixed_profile(ReceiverMap model) {
    return model == ReceiverMap::kPxW3u4 || model == ReceiverMap::kPxW3pe4 ||
        model == ReceiverMap::kPxW3pe5;
}

constexpr bool is_dual_system_profile(ReceiverMap model) {
    return model == ReceiverMap::kPxMlt5 || model == ReceiverMap::kPxMlt8pe3 || model == ReceiverMap::kPxMlt8pe5 ||
        model == ReceiverMap::kDtv02a4tsP || model == ReceiverMap::kPxM1ur ||
        model == ReceiverMap::kDtv021t1sU || model == ReceiverMap::kDtv02a1t1sU;
}

constexpr int receiver_count_for_model(ReceiverMap model) {
    switch (model) {
    case ReceiverMap::kPxQ3u4:
    case ReceiverMap::kPxQ3pe4:
    case ReceiverMap::kPxQ3pe5:
        return 8;
    case ReceiverMap::kPxW3u4:
    case ReceiverMap::kPxW3pe4:
    case ReceiverMap::kPxW3pe5:
    case ReceiverMap::kDtv02a4tsP:
        return 4;
    case ReceiverMap::kPxMlt8pe3:
        return 3;
    case ReceiverMap::kPxMlt5:
    case ReceiverMap::kPxMlt8pe5:
        return 5;
    case ReceiverMap::kPxM1ur:
    case ReceiverMap::kPxS1ur:
    case ReceiverMap::kDtv03a1tu:
    case ReceiverMap::kDtv021t1sU:
    case ReceiverMap::kDtv02a1t1sU:
        return 1;
    }
    return 0;
}

constexpr int bridge_count_for_model(ReceiverMap model) {
    return is_q3_fixed_profile(model) ? 2 : 1;
}

constexpr bool receiver_supports_terrestrial(ReceiverMap model, int receiver) {
    if (receiver < 0 || receiver >= receiver_count_for_model(model)) return false;
    if (is_q3_fixed_profile(model)) return receiver % 4 >= 2;
    if (is_w3_fixed_profile(model)) return receiver >= 2;
    if (model == ReceiverMap::kPxS1ur || model == ReceiverMap::kDtv03a1tu) return receiver == 0;
    return is_dual_system_profile(model);
}

constexpr bool receiver_supports_satellite(ReceiverMap model, int receiver) {
    if (receiver < 0 || receiver >= receiver_count_for_model(model)) return false;
    if (is_q3_fixed_profile(model)) return receiver % 4 < 2;
    if (is_w3_fixed_profile(model)) return receiver < 2;
    return is_dual_system_profile(model);
}

struct TunePlan {
    int receiver = -1;
    BroadcastSystem system = BroadcastSystem::kIsdbT;
    std::uint32_t frequency_khz = 0;
    SatelliteSelector satellite_selector = SatelliteSelector::kNone;
    std::uint16_t satellite_value = 0;
};

// Creates the complete px4-ts tune request from the mirakc channel token.
// The optional TSID is intentionally a string: syntax validation belongs here
// so the CLI and host tests exercise exactly the same fail-closed rules.
bool create_tune_plan(
    int receiver,
    std::string_view channel,
    std::optional<std::string_view> tsid,
    TunePlan* plan,
    std::string* error,
    ReceiverMap receiver_map = ReceiverMap::kPxQ3u4);

}  // namespace px4_adapter
