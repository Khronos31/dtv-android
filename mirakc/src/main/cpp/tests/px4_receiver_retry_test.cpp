#include "px4_receiver_retry.h"

#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

namespace {

[[noreturn]] void fail(const char* case_name) {
    std::cerr << "failed: " << case_name << '\n';
    std::exit(EXIT_FAILURE);
}

int receiver_argument(const std::vector<std::string>& arguments) {
    for (std::size_t index = 0; index + 1 < arguments.size(); ++index) {
        if (arguments[index] == "--receiver") return std::stoi(arguments[index + 1]);
    }
    fail("child argv contains --receiver");
}

void expect_child_receiver(
    const char* case_name,
    px4_adapter::ReceiverMap model,
    px4_adapter::BroadcastSystem system,
    int selected,
    std::size_t attempt,
    int expected) {
    std::vector<std::string> child_arguments{
        "px4-ts", "--device", px4_adapter::bridge_count_for_model(model) == 2
            ? "12345678901234" : "123456789012345",
        "--instance", "px4-test-device",
        "--receiver", std::to_string(selected), "--system",
        system == px4_adapter::BroadcastSystem::kIsdbT ? "isdb-t" : "isdb-s"
    };
    if (!px4_adapter::set_child_receiver_for_attempt(
            &child_arguments, model, system, selected, attempt) ||
        receiver_argument(child_arguments) != expected) {
        fail(case_name);
    }

    px4_adapter::TunePlan plan;
    std::string error;
    const char* channel = system == px4_adapter::BroadcastSystem::kIsdbT ? "27" : "BS15_0";
    if (!px4_adapter::create_tune_plan(expected, channel, std::nullopt, &plan, &error, model) ||
        plan.system != system) {
        fail("child argv receiver remains valid for model and broadcast system");
    }
}

}  // namespace

int main() {
    const auto q3u4 = px4_adapter::receiver_map_for_model("q3u4");
    const auto mlt5 = px4_adapter::receiver_map_for_model("mlt5");
    const auto m1ur = px4_adapter::receiver_map_for_model("m1ur");
    const auto s1ur = px4_adapter::receiver_map_for_model("s1ur");
    if (!q3u4 || *q3u4 != px4_adapter::ReceiverMap::kPxQ3u4 ||
        !mlt5 || *mlt5 != px4_adapter::ReceiverMap::kPxMlt5 ||
        !m1ur || *m1ur != px4_adapter::ReceiverMap::kPxM1ur ||
        !s1ur || *s1ur != px4_adapter::ReceiverMap::kPxS1ur ||
        px4_adapter::receiver_map_for_model("unknown")) {
        fail("explicit model names map to the supported receiver maps");
    }
    if (!px4_adapter::base_serial_matches_model(*q3u4, "12345678901234") ||
        !px4_adapter::base_serial_matches_model(*mlt5, "123456789012345") ||
        !px4_adapter::base_serial_matches_model(*m1ur, "123456789012345") ||
        !px4_adapter::base_serial_matches_model(*s1ur, "123456789012345") ||
        px4_adapter::base_serial_matches_model(*q3u4, "123456789012345") ||
        px4_adapter::base_serial_matches_model(*mlt5, "12345678901234") ||
        px4_adapter::base_serial_matches_model(*m1ur, "12345678901234") ||
        px4_adapter::base_serial_matches_model(*s1ur, "12345678901234")) {
        fail("base serial validation follows the explicit model");
    }

    using px4_adapter::BroadcastSystem;
    expect_child_receiver("Q3U4 GR attempt zero preserves selection", *q3u4,
                          BroadcastSystem::kIsdbT, 6, 0, 6);
    expect_child_receiver("Q3U4 GR retry stays on selected receiver", *q3u4,
                          BroadcastSystem::kIsdbT, 6, 49, 6);
    expect_child_receiver("Q3U4 satellite attempt zero preserves selection", *q3u4,
                          BroadcastSystem::kIsdbS, 5, 0, 5);
    expect_child_receiver("Q3U4 satellite retry stays on selected receiver", *q3u4,
                          BroadcastSystem::kIsdbS, 5, 49, 5);
    expect_child_receiver("MLT5 GR attempt zero preserves selection", *mlt5,
                          BroadcastSystem::kIsdbT, 4, 0, 4);
    expect_child_receiver("MLT5 GR retry stays on selected receiver", *mlt5,
                          BroadcastSystem::kIsdbT, 4, 49, 4);
    expect_child_receiver("MLT5 satellite attempt zero preserves selection", *mlt5,
                          BroadcastSystem::kIsdbS, 1, 0, 1);
    expect_child_receiver("MLT5 satellite retry stays on selected receiver", *mlt5,
                          BroadcastSystem::kIsdbS, 1, 49, 1);
    expect_child_receiver("M1UR GR retry stays on receiver zero", *m1ur,
                          BroadcastSystem::kIsdbT, 0, 1, 0);
    expect_child_receiver("M1UR satellite retry stays on receiver zero", *m1ur,
                          BroadcastSystem::kIsdbS, 0, 1, 0);
    expect_child_receiver("S1UR GR retry stays on receiver zero", *s1ur,
                          BroadcastSystem::kIsdbT, 0, 1, 0);

    struct ProfileCase {
        const char* argument;
        px4_adapter::ReceiverMap map;
        int bridge_count;
        int receiver_count;
        std::vector<int> terrestrial_receivers;
        std::vector<int> satellite_receivers;
    };
    const ProfileCase profiles[] = {
        {"q3u4", px4_adapter::ReceiverMap::kPxQ3u4, 2, 8, {2, 3, 6, 7}, {0, 1, 4, 5}},
        {"w3u4", px4_adapter::ReceiverMap::kPxW3u4, 1, 4, {2, 3}, {0, 1}},
        {"mlt5", px4_adapter::ReceiverMap::kPxMlt5, 1, 5, {0, 1, 2, 3, 4}, {0, 1, 2, 3, 4}},
        {"w3pe4", px4_adapter::ReceiverMap::kPxW3pe4, 1, 4, {2, 3}, {0, 1}},
        {"w3pe5", px4_adapter::ReceiverMap::kPxW3pe5, 1, 4, {2, 3}, {0, 1}},
        {"q3pe4", px4_adapter::ReceiverMap::kPxQ3pe4, 2, 8, {2, 3, 6, 7}, {0, 1, 4, 5}},
        {"q3pe5", px4_adapter::ReceiverMap::kPxQ3pe5, 2, 8, {2, 3, 6, 7}, {0, 1, 4, 5}},
        {"mlt8pe3", px4_adapter::ReceiverMap::kPxMlt8pe3, 1, 3, {0, 1, 2}, {0, 1, 2}},
        {"mlt8pe5", px4_adapter::ReceiverMap::kPxMlt8pe5, 1, 5, {0, 1, 2, 3, 4}, {0, 1, 2, 3, 4}},
        {"dtv02a4tsp", px4_adapter::ReceiverMap::kDtv02a4tsP, 1, 4, {0, 1, 2, 3}, {0, 1, 2, 3}},
        {"m1ur", px4_adapter::ReceiverMap::kPxM1ur, 1, 1, {0}, {0}},
        {"s1ur", px4_adapter::ReceiverMap::kPxS1ur, 1, 1, {0}, {}},
        {"dtv03a1tu", px4_adapter::ReceiverMap::kDtv03a1tu, 1, 1, {0}, {}},
        {"dtv021t1su", px4_adapter::ReceiverMap::kDtv021t1sU, 1, 1, {0}, {0}},
        {"dtv02a1t1su", px4_adapter::ReceiverMap::kDtv02a1t1sU, 1, 1, {0}, {0}},
    };
    for (const ProfileCase& profile : profiles) {
        const auto mapped = px4_adapter::receiver_map_for_model(profile.argument);
        if (!mapped || *mapped != profile.map) fail("every Android profile maps to its native adapter map");
        if (px4_adapter::bridge_count_for_model(profile.map) != profile.bridge_count ||
            px4_adapter::receiver_count_for_model(profile.map) != profile.receiver_count) {
            fail("native profile count matches independent upstream expectations");
        }
        const std::string valid_serial = profile.bridge_count == 2
            ? "12345678901234" : "123456789012345";
        const std::string invalid_serial = profile.bridge_count == 2
            ? "123456789012345" : "12345678901234";
        if (!px4_adapter::base_serial_matches_model(profile.map, valid_serial) ||
            px4_adapter::base_serial_matches_model(profile.map, invalid_serial)) {
            fail("serial length follows upstream enclosure bridge count");
        }
        const auto terrestrial = px4_adapter::receiver_pool(profile.map, BroadcastSystem::kIsdbT);
        const auto satellite = px4_adapter::receiver_pool(profile.map, BroadcastSystem::kIsdbS);
        if (terrestrial != profile.terrestrial_receivers || satellite != profile.satellite_receivers) {
            fail("native receiver pools match independent upstream expectations");
        }
        for (int receiver = 0; receiver <= profile.receiver_count; ++receiver) {
            px4_adapter::TunePlan plan;
            std::string error;
            const bool has_gr = px4_adapter::create_tune_plan(
                receiver, "13", std::nullopt, &plan, &error, profile.map);
            const bool has_satellite = px4_adapter::create_tune_plan(
                receiver, "BS15_0", std::nullopt, &plan, &error, profile.map);
            const bool expected_gr = std::find(profile.terrestrial_receivers.begin(),
                profile.terrestrial_receivers.end(), receiver) != profile.terrestrial_receivers.end();
            const bool expected_satellite = std::find(profile.satellite_receivers.begin(),
                profile.satellite_receivers.end(), receiver) != profile.satellite_receivers.end();
            if (has_gr != expected_gr || has_satellite != expected_satellite) {
                fail("tune-plan capabilities match independent upstream receiver lists");
            }
        }
        for (const auto& system_pool : {
                 std::pair{BroadcastSystem::kIsdbT, terrestrial},
                 std::pair{BroadcastSystem::kIsdbS, satellite}}) {
            if (system_pool.second.empty()) continue;
            for (const int selected : system_pool.second) {
                std::vector<std::string> arguments{
                    "px4-ts", "--device", valid_serial, "--instance", "px4-test-device",
                    "--receiver", std::to_string(selected), "--system",
                    system_pool.first == BroadcastSystem::kIsdbT ? "isdb-t" : "isdb-s"
                };
                for (const std::size_t attempt : {0U, 1U, 2U, 7U, 49U}) {
                    if (!px4_adapter::set_child_receiver_for_attempt(
                            &arguments, profile.map, system_pool.first, selected, attempt) ||
                        receiver_argument(arguments) != selected) {
                        fail("every retry preserves the selected physical receiver lease");
                    }
                }
            }
        }
    }
    if (px4_adapter::receiver_map_for_model("unknown"))
        fail("unknown profile arguments fail closed");

    std::vector<std::string> missing_receiver{"px4-ts", "--device", "123456789012345"};
    if (px4_adapter::set_child_receiver_for_attempt(
            &missing_receiver, *mlt5, BroadcastSystem::kIsdbT, 0, 0)) {
        fail("missing --receiver is rejected");
    }
    std::vector<std::string> duplicate_receiver{
        "px4-ts", "--receiver", "2", "--receiver", "3"
    };
    if (px4_adapter::set_child_receiver_for_attempt(
            &duplicate_receiver, *q3u4, BroadcastSystem::kIsdbT, 2, 0)) {
        fail("duplicate --receiver is rejected");
    }
    std::vector<std::string> incompatible_receiver{
        "px4-ts", "--receiver", "0"
    };
    if (px4_adapter::set_child_receiver_for_attempt(
            &incompatible_receiver, *q3u4, BroadcastSystem::kIsdbT, 0, 0)) {
        fail("receiver unsupported for requested system is rejected");
    }
    std::cout << "px4 receiver-retry tests: PASS\n";
    return EXIT_SUCCESS;
}
