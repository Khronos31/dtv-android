#include "px4_receiver_retry.h"

#include <cstdlib>
#include <iostream>
#include <string>
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
        "px4-ts", "--device", model == px4_adapter::ReceiverMap::kPxQ3u4
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
    expect_child_receiver("Q3U4 GR retry stays in Q3U4 pool", *q3u4,
                          BroadcastSystem::kIsdbT, 6, 1, 2);
    expect_child_receiver("Q3U4 satellite attempt zero preserves selection", *q3u4,
                          BroadcastSystem::kIsdbS, 5, 0, 5);
    expect_child_receiver("Q3U4 satellite retry stays in Q3U4 pool", *q3u4,
                          BroadcastSystem::kIsdbS, 5, 1, 0);
    expect_child_receiver("MLT5 GR attempt zero preserves selection", *mlt5,
                          BroadcastSystem::kIsdbT, 4, 0, 4);
    expect_child_receiver("MLT5 GR retry stays in MLT5 pool", *mlt5,
                          BroadcastSystem::kIsdbT, 4, 1, 0);
    expect_child_receiver("MLT5 satellite attempt zero preserves selection", *mlt5,
                          BroadcastSystem::kIsdbS, 1, 0, 1);
    expect_child_receiver("MLT5 satellite retry stays in MLT5 pool", *mlt5,
                          BroadcastSystem::kIsdbS, 1, 1, 0);
    expect_child_receiver("M1UR GR retry stays on receiver zero", *m1ur,
                          BroadcastSystem::kIsdbT, 0, 1, 0);
    expect_child_receiver("M1UR satellite retry stays on receiver zero", *m1ur,
                          BroadcastSystem::kIsdbS, 0, 1, 0);
    expect_child_receiver("S1UR GR retry stays on receiver zero", *s1ur,
                          BroadcastSystem::kIsdbT, 0, 1, 0);

    std::vector<std::string> missing_receiver{"px4-ts", "--device", "123456789012345"};
    if (px4_adapter::set_child_receiver_for_attempt(
            &missing_receiver, *mlt5, BroadcastSystem::kIsdbT, 0, 0)) {
        fail("missing --receiver is rejected");
    }
    std::cout << "px4 receiver-retry tests: PASS\n";
    return EXIT_SUCCESS;
}
