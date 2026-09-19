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
    std::string* error);

}  // namespace px4_adapter
