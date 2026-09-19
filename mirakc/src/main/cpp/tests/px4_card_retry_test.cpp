#include "px4_card_retry.h"

#include <cstdlib>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {

struct FakeHandle {
    std::uint64_t handle = 0;
};

enum class FakeError {
    kBusy,
    kPermanent,
};

struct FakeResult {
    bool success = false;
    FakeHandle result;
    FakeError failure = FakeError::kPermanent;

    explicit operator bool() const { return success; }
    FakeError error() const { return failure; }
    FakeHandle value() const { return result; }
};

[[noreturn]] void fail(const char* case_name) {
    std::cerr << "failed: " << case_name << '\n';
    std::exit(EXIT_FAILURE);
}

}  // namespace

int main() {
    int attempts = 0;
    std::vector<unsigned int> delays;
    std::uint64_t handle = 0;
    const bool connected = px4_adapter::connect_shared_with_retry(
        [&]() {
            ++attempts;
            return FakeResult{attempts == 3, FakeHandle{1234}, FakeError::kBusy};
        },
        [](FakeError error) { return error == FakeError::kBusy; },
        [&](unsigned int delay) { delays.push_back(delay); },
        &handle);
    if (!connected || attempts != 3 || handle != 1234 || delays.size() != 2 ||
        delays[0] != px4_adapter::kCardConnectRetryDelayUs ||
        delays[1] != px4_adapter::kCardConnectRetryDelayUs) {
        fail("transient retry succeeds");
    }

    attempts = 0;
    delays.clear();
    handle = 0;
    const bool exhausted = px4_adapter::connect_shared_with_retry(
        [&]() {
            ++attempts;
            return FakeResult{false, FakeHandle{0}, FakeError::kBusy};
        },
        [](FakeError) { return true; },
        [&](unsigned int delay) { delays.push_back(delay); },
        &handle);
    if (exhausted || attempts != px4_adapter::kCardConnectMaxAttempts ||
        handle != 0 || delays.size() != px4_adapter::kCardConnectMaxAttempts - 1U) {
        fail("permanent failure is bounded");
    }

    if (px4_adapter::connect_shared_with_retry(
            []() { return FakeResult{true, FakeHandle{1}, FakeError::kPermanent}; },
            [](FakeError) { return true; },
            [](unsigned int) {}, nullptr)) {
        fail("null output is rejected");
    }

    attempts = 0;
    const bool permanent = px4_adapter::connect_shared_with_retry(
        [&]() {
            ++attempts;
            return FakeResult{false, FakeHandle{0}, FakeError::kPermanent};
        },
        [](FakeError error) { return error == FakeError::kBusy; },
        [](unsigned int) {},
        &handle);
    if (permanent || attempts != 1) fail("permanent error fails fast");

    std::cout << "px4 card retry tests: PASS\n";
    return EXIT_SUCCESS;
}
