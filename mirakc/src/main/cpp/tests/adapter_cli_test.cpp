#include "adapter_cli.h"

#include <dtv/userland/tuning.h>

#include <cstdio>
#include <optional>
#include <string>
#include <vector>

namespace {

bool resolve_arguments(const std::vector<std::string>& arguments,
                       dtv::userland::wire::Selection selection,
                       std::uint16_t selection_value) {
    std::string channel;
    std::optional<std::string> tsid;
    for (std::size_t index = 1U; index + 1U < arguments.size(); ++index) {
        if (arguments[index] == "--channel") channel = arguments[index + 1U];
        if (arguments[index] == "--tsid") tsid = arguments[index + 1U];
    }
    dtv::tuning::Request request;
    request.channel = channel;
    if (tsid.has_value()) {
        unsigned int value = 0U;
        if (!mirakc_adapter::decimal_token(*tsid, 65534U, &value)) return false;
        request.tsid = static_cast<std::uint16_t>(value);
    }
    const auto resolved = dtv::tuning::resolve(request);
    return resolved && resolved.value().selection == selection &&
           resolved.value().selection_value == selection_value;
}

bool check_normalization(const std::string& requested_tsid,
                         bool requested,
                         const std::string& expected_channel,
                         bool expected_tsid,
                         const std::string& expected_tsid_value,
                         dtv::userland::wire::Selection expected_selection,
                         std::uint16_t expected_value) {
    mirakc_adapter::NormalizedSelection selection;
    if (!mirakc_adapter::normalize_selection("BS01_5", requested_tsid,
                                              requested, &selection)) return false;
    if (selection.channel != expected_channel ||
        selection.have_tsid != expected_tsid ||
        selection.tsid != expected_tsid_value) return false;
    const auto arguments = mirakc_adapter::build_ts_arguments(
        "/app/libpix-ts.so", "pix-01", "0", selection, "/tmp/p4");
    return resolve_arguments(arguments, expected_selection, expected_value);
}

bool check_receiver_tokens() {
    unsigned int receiver = 0U;
    if (!mirakc_adapter::receiver_token("0", &receiver) || receiver != 0U ||
        !mirakc_adapter::receiver_token("65535", &receiver) || receiver != 65535U) {
        return false;
    }
    for (const std::string invalid : {"-1", "+1", "01", "65536", "999999999999999999999"}) {
        if (mirakc_adapter::receiver_token(invalid, &receiver)) return false;
    }
    return true;
}

bool check_product_arguments(const std::string& program,
                             const std::string& instance,
                             const std::string& receiver,
                             const std::string& runtime_dir) {
    mirakc_adapter::NormalizedSelection selection;
    if (!mirakc_adapter::normalize_selection("T13", "", false, &selection)) return false;
    const auto arguments = mirakc_adapter::build_ts_arguments(
        program, instance, receiver, selection, runtime_dir);
    const std::vector<std::string> expected{
        program, "--instance", instance, "--receiver", receiver,
        "--channel", "T13", "--runtime-dir", runtime_dir,
        "--output", "-"};
    return arguments == expected;
}

}  // namespace

int main() {
    using dtv::userland::wire::Selection;
    if (!check_receiver_tokens()) {
        std::fprintf(stderr, "PIX receiver token did not preserve the u16 boundary\n");
        return 1;
    }
    if (!check_product_arguments("/app/libpix-ts.so", "pix-0123456789ab",
                                 "65535", "/data/user/0/dev.khronos31.mirakc/files/x1") ||
        !check_product_arguments("/app/libasicen-ts.so", "asicen-0123456789ab",
                                 "0", "/data/user/0/dev.khronos31.mirakc/files/a1")) {
        std::fprintf(stderr, "native product TS argv did not preserve shared runtime identity\n");
        return 1;
    }
    if (!check_normalization("0", true, "BS01_5", false, "",
                             Selection::slot, 5U)) {
        std::fprintf(stderr, "TSID zero did not fall back to the BS slot\n");
        return 1;
    }
    if (!check_normalization("42", true, "BS01", true, "42",
                             Selection::tsid, 42U)) {
        std::fprintf(stderr, "positive TSID did not override the BS slot\n");
        return 1;
    }
    mirakc_adapter::NormalizedSelection cs;
    if (!mirakc_adapter::normalize_selection("CS2", "0", true, &cs) ||
        cs.have_tsid || cs.channel != "CS2") {
        std::fprintf(stderr, "CS zero TSID fallback changed channel selection\n");
        return 1;
    }
    const auto cs_arguments = mirakc_adapter::build_ts_arguments(
        "/app/libpix-ts.so", "pix-01", "0", cs, "/tmp/p4");
    if (!resolve_arguments(cs_arguments, Selection::slot, 0U)) {
        std::fprintf(stderr, "Core CS default-slot behavior changed\n");
        return 1;
    }
    for (const std::string invalid : {"-1", "+1", "01", "65535", "65536"}) {
        if (mirakc_adapter::normalize_selection("BS01_5", invalid, true, &cs)) {
            std::fprintf(stderr, "invalid TSID was accepted: %s\n", invalid.c_str());
            return 1;
        }
    }
    std::puts("adapter argv resolves through pinned Core tuning: PASS");
    return 0;
}
