#include "px4_tune_plan.h"

#include <cstdlib>
#include <iostream>
#include <optional>
#include <string>
#include <string_view>

namespace {

using px4_adapter::BroadcastSystem;
using px4_adapter::SatelliteSelector;
using px4_adapter::TunePlan;

[[noreturn]] void fail(const char* case_name) {
    std::cerr << "failed: " << case_name << '\n';
    std::exit(EXIT_FAILURE);
}

void expect_plan(
    const char* case_name,
    int receiver,
    std::string_view channel,
    std::optional<std::string_view> tsid,
    BroadcastSystem system,
    std::uint32_t frequency,
    SatelliteSelector selector,
    std::uint16_t value,
    px4_adapter::ReceiverMap receiver_map = px4_adapter::ReceiverMap::kPxQ3u4) {
    TunePlan plan;
    std::string error;
    if (!px4_adapter::create_tune_plan(
            receiver, channel, tsid, &plan, &error, receiver_map) ||
        plan.system != system || plan.frequency_khz != frequency ||
        plan.satellite_selector != selector || plan.satellite_value != value) {
        fail(case_name);
    }
}

void expect_rejected(const char* case_name, int receiver, std::string_view channel,
                     std::optional<std::string_view> tsid = std::nullopt,
                     px4_adapter::ReceiverMap receiver_map = px4_adapter::ReceiverMap::kPxQ3u4) {
    TunePlan plan;
    std::string error;
    if (px4_adapter::create_tune_plan(
            receiver, channel, tsid, &plan, &error, receiver_map)) {
        fail(case_name);
    }
}

}  // namespace

int main() {
    for (int receiver : {2, 3, 6, 7}) {
        expect_plan("GR receiver", receiver, "13", std::nullopt,
                    BroadcastSystem::kIsdbT, 473142, SatelliteSelector::kNone, 0);
        expect_plan("GR upper boundary", receiver, "62", std::nullopt,
                    BroadcastSystem::kIsdbT, 767142, SatelliteSelector::kNone, 0);
    }
    for (int receiver : {0, 1, 4, 5}) {
        expect_plan("BS lower boundary", receiver, "BS01_0", std::nullopt,
                    BroadcastSystem::kIsdbS, 1049480, SatelliteSelector::kSlot, 0);
        expect_plan("BS upper boundary", receiver, "BS23_11", std::nullopt,
                    BroadcastSystem::kIsdbS, 1471440, SatelliteSelector::kSlot, 11);
        expect_plan("BS TSID override", receiver, "BS15_2",
                    std::optional<std::string_view>(std::string_view("4660")),
                    BroadcastSystem::kIsdbS, 1318000, SatelliteSelector::kStreamId, 4660);
        expect_plan("CS lower boundary", receiver, "CS2", std::nullopt,
                    BroadcastSystem::kIsdbS, 1613000, SatelliteSelector::kSlot, 0);
        expect_plan("CS upper boundary", receiver, "CS24", std::nullopt,
                    BroadcastSystem::kIsdbS, 2053000, SatelliteSelector::kSlot, 0);
    }

    expect_rejected("unknown receiver", 8, "13");
    expect_rejected("GR on satellite receiver", 0, "13");
    expect_rejected("satellite on terrestrial receiver", 2, "BS01_0");
    expect_rejected("CS on terrestrial receiver", 2, "CS2");
    expect_rejected("GR TSID", 2, "13", std::optional<std::string_view>("1"));
    expect_rejected("CS TSID", 0, "CS2", std::optional<std::string_view>("1"));
    expect_plan("BS zero TSID", 0, "BS01_0", std::optional<std::string_view>("0"),
                BroadcastSystem::kIsdbS, 1049480, SatelliteSelector::kStreamId, 0);
    expect_plan("BS maximum usable TSID", 0, "BS01_0",
                std::optional<std::string_view>("65534"),
                BroadcastSystem::kIsdbS, 1049480, SatelliteSelector::kStreamId, 65534);
    expect_rejected("BS reserved TSID", 0, "BS01_0", std::optional<std::string_view>("65535"));
    expect_rejected("BS out of range TSID", 0, "BS01_0", std::optional<std::string_view>("65536"));
    expect_rejected("BS negative TSID", 0, "BS01_0", std::optional<std::string_view>("-1"));
    expect_rejected("BS signed TSID", 0, "BS01_0", std::optional<std::string_view>("+1"));
    expect_rejected("BS whitespace TSID", 0, "BS01_0", std::optional<std::string_view>(" 1"));
    expect_rejected("BS empty TSID", 0, "BS01_0", std::optional<std::string_view>(""));
    expect_rejected("BS duplicate-style TSID syntax", 0, "BS01_0", std::optional<std::string_view>("01"));
    expect_rejected("BS leading-zero transponder", 0, "BS1_0");
    expect_rejected("BS padded slot", 0, "BS01_00");
    expect_rejected("BS even transponder", 0, "BS02_0");
    expect_rejected("BS slot out of range", 0, "BS23_12");
    expect_rejected("CS leading zero", 0, "CS02");
    expect_rejected("CS odd channel", 0, "CS3");
    expect_rejected("CS out of range", 0, "CS26");
    expect_rejected("GR leading zero", 2, "013");
    expect_rejected("GR signed channel", 2, "+13");
    expect_rejected("GR whitespace channel", 2, " 13");
    expect_rejected("BS whitespace channel", 0, "BS01_0 ");
    expect_rejected("GR out of range", 2, "63");

    std::string null_plan_error;
    if (px4_adapter::create_tune_plan(2, "13", std::nullopt, nullptr, &null_plan_error)) {
        fail("null TunePlan output");
    }

    for (int receiver : {0, 1, 2, 3, 4}) {
        expect_plan("MLT GR", receiver, "27", std::nullopt,
                    BroadcastSystem::kIsdbT, 557142, SatelliteSelector::kNone, 0,
                    px4_adapter::ReceiverMap::kPxMlt5);
        expect_plan("MLT BS", receiver, "BS15_0", std::nullopt,
                    BroadcastSystem::kIsdbS, 1318000, SatelliteSelector::kSlot, 0,
                    px4_adapter::ReceiverMap::kPxMlt5);
    }
    expect_rejected("MLT receiver 5", 5, "13", std::nullopt,
                    px4_adapter::ReceiverMap::kPxMlt5);
    expect_rejected("Q3U4 map rejects MLT-only overlap", 8, "BS01_0");

    std::cout << "px4 tune-plan tests: PASS\n";
    return EXIT_SUCCESS;
}
