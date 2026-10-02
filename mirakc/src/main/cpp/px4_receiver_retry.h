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
    if (model == "mlt5") return ReceiverMap::kPxMlt5;
    return std::nullopt;
}

inline bool base_serial_matches_model(ReceiverMap model, std::string_view serial) {
    const std::size_t expected_length = model == ReceiverMap::kPxQ3u4 ? 14U : 15U;
    if (serial.size() != expected_length) return false;
    return std::all_of(serial.begin(), serial.end(), [](char value) {
        return value >= '0' && value <= '9';
    });
}

inline std::vector<int> receiver_pool(ReceiverMap model, BroadcastSystem system) {
    if (model == ReceiverMap::kPxMlt5) return {0, 1, 2, 3, 4};
    return system == BroadcastSystem::kIsdbT
        ? std::vector<int>{2, 3, 6, 7}
        : std::vector<int>{0, 1, 4, 5};
}

inline std::optional<int> receiver_for_attempt(
    ReceiverMap model,
    BroadcastSystem system,
    int selected_receiver,
    std::size_t attempt) {
    const std::vector<int> pool = receiver_pool(model, system);
    if (std::find(pool.begin(), pool.end(), selected_receiver) == pool.end()) {
        return std::nullopt;
    }
    std::vector<int> order{selected_receiver};
    for (const int receiver : pool) {
        if (receiver != selected_receiver) order.push_back(receiver);
    }
    return order[attempt % order.size()];
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
