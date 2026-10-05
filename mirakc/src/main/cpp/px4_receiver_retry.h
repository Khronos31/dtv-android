#pragma once

#include "px4_tune_plan.h"

#include <algorithm>
#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace px4_adapter {

inline std::optional<ReceiverMap> receiver_map_for_model(std::string_view model) {
    if (model == "q3u4") return ReceiverMap::kPxQ3u4;
    if (model == "w3u4") return ReceiverMap::kPxW3u4;
    if (model == "mlt5") return ReceiverMap::kPxMlt5;
    if (model == "w3pe4") return ReceiverMap::kPxW3pe4;
    if (model == "w3pe5") return ReceiverMap::kPxW3pe5;
    if (model == "q3pe4") return ReceiverMap::kPxQ3pe4;
    if (model == "q3pe5") return ReceiverMap::kPxQ3pe5;
    if (model == "mlt8pe3") return ReceiverMap::kPxMlt8pe3;
    if (model == "mlt8pe5") return ReceiverMap::kPxMlt8pe5;
    if (model == "dtv02a4tsp") return ReceiverMap::kDtv02a4tsP;
    if (model == "m1ur") return ReceiverMap::kPxM1ur;
    if (model == "s1ur") return ReceiverMap::kPxS1ur;
    if (model == "dtv03a1tu") return ReceiverMap::kDtv03a1tu;
    if (model == "dtv021t1su") return ReceiverMap::kDtv021t1sU;
    if (model == "dtv02a1t1su") return ReceiverMap::kDtv02a1t1sU;
    return std::nullopt;
}

inline bool base_serial_matches_model(ReceiverMap model, std::string_view serial) {
    const std::size_t expected_length = bridge_count_for_model(model) == 2 ? 14U : 15U;
    if (serial.size() != expected_length) return false;
    return std::all_of(serial.begin(), serial.end(), [](char value) {
        return value >= '0' && value <= '9';
    });
}

inline std::vector<int> receiver_pool(ReceiverMap model, BroadcastSystem system) {
    std::vector<int> pool;
    for (int receiver = 0; receiver < receiver_count_for_model(model); ++receiver) {
        const bool supported = system == BroadcastSystem::kIsdbT
            ? receiver_supports_terrestrial(model, receiver)
            : receiver_supports_satellite(model, receiver);
        if (supported) pool.push_back(receiver);
    }
    return pool;
}

inline std::optional<int> receiver_for_attempt(
    ReceiverMap model,
    BroadcastSystem system,
    int selected_receiver,
    std::size_t attempt) {
    (void)attempt;
    const std::vector<int> pool = receiver_pool(model, system);
    if (std::find(pool.begin(), pool.end(), selected_receiver) == pool.end()) {
        return std::nullopt;
    }
    // `selected_receiver` belongs to one mirakc TunerManager lease. Retrying
    // its child on another physical receiver would bypass that lease and can
    // contend with a different logical tuner. Retries may wait for this same
    // receiver to become available, but must never retarget another lease.
    return selected_receiver;
}

// This mutates the exact argv storage passed to execv by px4_adapter.cpp.
inline bool set_child_receiver_for_attempt(
    std::vector<std::string>* child_arguments,
    ReceiverMap model,
    BroadcastSystem system,
    int selected_receiver,
    std::size_t attempt) {
    if (child_arguments == nullptr) return false;
    std::size_t receiver_index = child_arguments->size();
    for (std::size_t index = 0; index < child_arguments->size(); ++index) {
        if ((*child_arguments)[index] != "--receiver") continue;
        if (receiver_index != child_arguments->size() || index + 1 >= child_arguments->size()) {
            return false;
        }
        receiver_index = index + 1;
    }
    if (receiver_index == child_arguments->size()) return false;
    const std::optional<int> receiver =
        receiver_for_attempt(model, system, selected_receiver, attempt);
    if (!receiver.has_value()) return false;
    (*child_arguments)[receiver_index] = std::to_string(*receiver);
    return true;
}

}  // namespace px4_adapter
