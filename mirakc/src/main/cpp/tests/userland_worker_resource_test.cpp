#include <dtv/userland/host_daemon/card_service.h>
#include <dtv/userland/host_daemon/control_workers.h>
#include <px4/tuner_service.h>

#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <thread>

namespace {

using namespace px4::userland;
using namespace px4::userland::ipc::posix;

class Backend final : public TunerServiceBackend {
public:
    std::uint8_t receiver_count() const noexcept override { return 1U; }
    Result<void> open_receiver(std::uint8_t) noexcept override {
        return Result<void>::success();
    }
    Result<void> tune_terrestrial(std::uint8_t, std::uint32_t,
                                  std::uint32_t) noexcept override {
        return Result<void>::success();
    }
    Result<void> tune_satellite(std::uint8_t, std::uint32_t,
                                std::uint32_t) noexcept override {
        return Result<void>::success();
    }
    Result<bool> is_locked(std::uint8_t, ipc::System) noexcept override {
        return Result<bool>::success(true);
    }
    Result<void> select_satellite_slot(std::uint8_t, std::uint8_t,
                                       std::uint32_t) noexcept override {
        return Result<void>::success();
    }
    Result<void> select_satellite_tsid(std::uint8_t, std::uint16_t,
                                       std::uint32_t) noexcept override {
        return Result<void>::success();
    }
    Result<void> close_receiver(std::uint8_t) noexcept override {
        return Result<void>::success();
    }
};

class Nonce final : public TunerNonceSource {
public:
    Result<std::array<std::uint8_t, ipc::kNonceLength>> generate() noexcept override {
        return Result<std::array<std::uint8_t, ipc::kNonceLength>>::success({{1U}});
    }
};

class Time final : public TunerServiceTime {
public:
    std::uint64_t monotonic_ms() noexcept override { return 0U; }
    void sleep_ms(std::uint32_t) noexcept override {}
};

class CardBackend final : public CardServiceBackend {
public:
    Result<void> set_power(bool) noexcept override { return Result<void>::success(); }
    Result<void> initialize_uart() noexcept override { return Result<void>::success(); }
    Result<bool> detect_card() noexcept override { return Result<bool>::success(false); }
};

class CardSession final : public CardProtocolSession {
public:
    Result<void> initialize() noexcept override { return Result<void>::success(); }
    Result<std::size_t> transmit(ByteView, MutableByteView) noexcept override {
        return Result<std::size_t>::success(0U);
    }
    bool initialized() const noexcept override { return false; }
    const CardAtrBytes& atr() const noexcept override { return atr_; }
    void invalidate() noexcept override {}
private:
    CardAtrBytes atr_{};
};

std::size_t thread_count() {
    DIR* directory = ::opendir("/proc/self/task");
    if (directory == nullptr) return 0U;
    std::size_t count = 0U;
    while (const dirent* entry = ::readdir(directory)) {
        if (entry->d_name[0] != '.') ++count;
    }
    ::closedir(directory);
    return count;
}

bool wait_for_threads(std::size_t expected) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < deadline) {
        if (thread_count() == expected) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return thread_count() == expected;
}

bool failure_cleans_started_receiver_lane(TunerService& tuner,
                                           CardService& card,
                                           std::size_t baseline_threads) {
    ControlWorkerStartupOptions options;
    options.fail_lane = 4;
    ControlExecutionLayout execution{};
    execution.receiver_count = 2U;
    execution.receiver_lane[0] = ControlWorkerLane::tuner_dev1;
    execution.receiver_lane[1] = ControlWorkerLane::tuner_dev2;
    const auto created = ControlWorkerLanes::create_for_test(
        card, tuner, options, nullptr, execution);
    return !created && wait_for_threads(baseline_threads);
}

}  // namespace

int main(int argc, char** argv) {
    const bool expect_baseline = argc == 2 &&
                                 std::strcmp(argv[1], "--baseline") == 0;
    if (argc > 2 || (argc == 2 && !expect_baseline)) return 2;
    Backend backend;
    Nonce nonce;
    Time time;
    TunerService tuner(backend, nonce, time);
    CardBackend card_backend;
    CardSession card_session;
    CardService card(card_backend, card_session);
    const std::size_t baseline_threads = thread_count();
    if (baseline_threads == 0U) return 1;

    ControlWorkerStartupOptions options;
    ControlExecutionLayout single_receiver{};
    single_receiver.receiver_count = 1U;
    single_receiver.receiver_lane[0] = ControlWorkerLane::tuner_dev2;
    auto created = ControlWorkerLanes::create_for_test(
        card, tuner, options, nullptr, single_receiver);
    const std::size_t expected_worker_threads = expect_baseline ? 3U : 4U;
    if (!created || created.value()->live_thread_count() != expected_worker_threads ||
        !wait_for_threads(baseline_threads + expected_worker_threads)) {
        std::fprintf(stderr, "single receiver worker allocation exceeded its bound\n");
        if (created) (void)created.value()->stop_and_join();
        return 1;
    }
    if (!created.value()->stop_and_join() ||
        created.value()->live_thread_count() != 0U ||
        !wait_for_threads(baseline_threads)) {
        std::fprintf(stderr, "single receiver workers did not stop cleanly\n");
        return 1;
    }
    created.value().reset();

    if (!expect_baseline &&
        !failure_cleans_started_receiver_lane(tuner, card, baseline_threads)) {
        std::fprintf(stderr, "receiver lane startup failure leaked worker threads\n");
        return 1;
    }
    std::puts(expect_baseline
        ? "baseline shared worker count recorded"
        : "single receiver worker bound and startup-failure cleanup: PASS");
    return 0;
}
