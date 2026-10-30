#include <dtv/userland/host_daemon/card_service.h>
#include <dtv/userland/host_daemon/control_workers.h>
#include <px4/host_authority_owner.h>
#include <px4/tuner_service.h>

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <poll.h>
#include <string>
#include <thread>

namespace {

namespace w = dtv::userland::wire;
using namespace px4::userland;
using namespace px4::userland::ipc::posix;

class Backend final : public TunerServiceBackend {
public:
    Result<void> open_receiver(std::uint8_t receiver) noexcept override {
        if (receiver == 0U && block_receiver0.load()) {
            std::unique_lock<std::mutex> lock(mutex_);
            receiver0_entered = true;
            condition_.notify_all();
            condition_.wait(lock, [this]() { return !block_receiver0.load(); });
        }
        return Result<void>::success();
    }
    Result<void> tune_terrestrial(std::uint8_t receiver,
                                  std::uint32_t, std::uint32_t) noexcept override {
        if (receiver == 0U && block_tune_receiver0.load()) {
            std::unique_lock<std::mutex> lock(mutex_);
            receiver0_tune_entered = true;
            condition_.notify_all();
            const bool released = condition_.wait_for(lock, std::chrono::seconds(5),
                [this] { return !block_tune_receiver0.load(); });
            if (!released) return Result<void>::failure(Error::TIMEOUT);
        }
        if (receiver == 2U) {
            std::lock_guard<std::mutex> lock(mutex_);
            receiver2_tune_entered = true;
            condition_.notify_all();
        }
        return Result<void>::success();
    }
    Result<void> tune_satellite(std::uint8_t receiver, std::uint32_t,
                                std::uint32_t) noexcept override {
        if (receiver == 0U && block_tune_receiver0.load()) {
            std::unique_lock<std::mutex> lock(mutex_);
            receiver0_tune_entered = true;
            condition_.notify_all();
            const bool released = condition_.wait_for(lock, std::chrono::seconds(5),
                [this] { return !block_tune_receiver0.load(); });
            if (!released) return Result<void>::failure(Error::TIMEOUT);
        }
        return Result<void>::success();
    }
    Result<TunerHzProgramming> tune_terrestrial_hz(
        std::uint8_t receiver, std::uint64_t, std::uint32_t) noexcept override {
        if (receiver == 2U) {
            std::unique_lock<std::mutex> lock(mutex_);
            receiver2_tune_entered = true;
            condition_.notify_all();
            if (block_tune_receiver2.load()) {
                tune_receiver2_active.store(true);
                condition_.notify_all();
                const bool released = condition_.wait_for(lock, std::chrono::seconds(5),
                    [this] { return !block_tune_receiver2.load(); });
                tune_receiver2_active.store(false);
                if (!released)
                    return Result<TunerHzProgramming>::failure(Error::TIMEOUT);
            }
        }
        return Result<TunerHzProgramming>::success({});
    }
    bool supports_internal_card_observation() const noexcept override { return true; }
    w::Optional<bool> sample_internal_card_inserted() noexcept override {
        if (tune_receiver2_active.load())
            status_during_tune.store(true);
        status_calls.fetch_add(1U);
        return w::Optional<bool>{true, false};
    }
    TunerStrictProfile strict_profile(std::uint8_t receiver,
                                      ipc::System system) const noexcept override {
        TunerStrictProfile profile;
        if (receiver == 2U && system == ipc::System::ISDB_T) {
            profile.native_hz = true;
            profile.min_hz = 400000000U;
            profile.max_hz = 800000000U;
        }
        return profile;
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
    Result<void> set_independent_lnb_power(bool enabled) noexcept override {
        lnb_enabled = enabled;
        lnb_called = true;
        return Result<void>::success();
    }
    Result<void> close_receiver(std::uint8_t) noexcept override {
        return Result<void>::success();
    }

    bool wait_receiver0() {
        std::unique_lock<std::mutex> lock(mutex_);
        return condition_.wait_for(lock, std::chrono::seconds(3),
                                   [this]() { return receiver0_entered; });
    }
    bool wait_tune_receiver0() {
        std::unique_lock<std::mutex> lock(mutex_);
        return condition_.wait_for(lock, std::chrono::seconds(3),
                                   [this]() { return receiver0_tune_entered; });
    }
    bool wait_tune_receiver2() {
        std::unique_lock<std::mutex> lock(mutex_);
        return condition_.wait_for(lock, std::chrono::seconds(3),
                                   [this]() { return receiver2_tune_entered; });
    }
    bool wait_blocked_tune_receiver2() {
        std::unique_lock<std::mutex> lock(mutex_);
        return condition_.wait_for(lock, std::chrono::seconds(3),
            [this]() { return tune_receiver2_active.load(); });
    }
    void release_receiver0() {
        block_receiver0.store(false);
        condition_.notify_all();
    }
    void release_tune_receiver0() {
        block_tune_receiver0.store(false);
        condition_.notify_all();
    }
    void release_tune_receiver2() {
        block_tune_receiver2.store(false);
        condition_.notify_all();
    }

    std::atomic<bool> block_receiver0{true};
    std::atomic<bool> block_tune_receiver0{false};
    std::atomic<bool> block_tune_receiver2{false};
    std::atomic<bool> lnb_called{false};
    std::atomic<bool> lnb_enabled{false};
    std::atomic<bool> status_during_tune{false};
    std::atomic<unsigned> status_calls{0U};

private:
    std::mutex mutex_;
    std::condition_variable condition_;
    bool receiver0_entered = false;
    bool receiver0_tune_entered = false;
    bool receiver2_tune_entered = false;
    std::atomic<bool> tune_receiver2_active{false};
};

class Nonce final : public TunerNonceSource {
public:
    Result<std::array<std::uint8_t, ipc::kNonceLength>> generate() noexcept override {
        std::array<std::uint8_t, ipc::kNonceLength> nonce{};
        nonce[0] = 1U;
        return Result<std::array<std::uint8_t, ipc::kNonceLength>>::success(nonce);
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

class UnblockGuard final {
public:
    explicit UnblockGuard(Backend& backend) : backend_(backend) {}
    ~UnblockGuard() { backend_.release_receiver0(); }
private:
    Backend& backend_;
};

class TuneUnblockGuard final {
public:
    explicit TuneUnblockGuard(Backend& backend) : backend_(backend) {}
    ~TuneUnblockGuard() { backend_.release_tune_receiver0(); }
private:
    Backend& backend_;
};

class AuthorityExecutor final : public dtv::userland::detail::ControlAuthorityExecutor {
public:
    bool submit(const dtv::userland::detail::AuthorityJob& value) noexcept override {
        if (has_job) return false;
        job = value;
        has_job = true;
        return true;
    }
    dtv::userland::detail::AuthorityJob take() noexcept {
        has_job = false;
        return job;
    }
    dtv::userland::detail::AuthorityJob job{};
    bool has_job = false;
};

class AuthoritySessionGuard final {
public:
    AuthoritySessionGuard(dtv::userland::detail::ControlAuthority& authority,
                          dtv::userland::detail::ControlServiceSession& session)
        : authority_(authority), session_(session) {}
    ~AuthoritySessionGuard() {
        if (session_.connected()) (void)authority_.close(session_);
    }
private:
    dtv::userland::detail::ControlAuthority& authority_;
    dtv::userland::detail::ControlServiceSession& session_;
};

template <std::size_t N>
w::WString<N> wire_text(const char* value) {
    w::WString<N> result;
    while (value[result.length] != '\0') {
        result.bytes[result.length] = value[result.length];
        ++result.length;
    }
    return result;
}

w::ListResponse authority_list() {
    w::ListResponse list;
    for (std::size_t i = 0U; i < list.epoch.size(); ++i)
        list.epoch[i] = static_cast<std::uint8_t>(i + 1U);
    list.producer.product = wire_text<32U>("native-concurrency-test");
    list.producer.version = wire_text<64U>("1");
    list.topology.state = w::TopologyState::complete;
    list.topology.expected_function_count.present = true;
    list.topology.expected_function_count.value = 1U;
    list.functions.count = 1U;
    auto& function = list.functions.items[0U];
    function.id = 1U;
    function.vid = 0x1234U;
    function.pid = 0x5678U;
    function.mode = w::Mode::runtime;
    function.serial_state = w::SerialState::absent;
    function.identification_reason = w::IdentificationReason::descriptor_unreadable;
    function.present = true;
    list.enclosure.present = true;
    list.enclosure.value.identity.serial_state = w::IdentitySerialState::absent;
    list.receivers.present = true;
    list.receivers.value.count = 2U;
    for (std::size_t i = 0U; i < 2U; ++i) {
        auto& receiver = list.receivers.value.items[i];
        receiver.id = i == 0U ? 0U : 2U;
        receiver.systems.mask = 1U;
        receiver.binding.present = true;
        receiver.binding.value.function_id = 1U;
        receiver.binding.value.local_id = receiver.id;
    }
    list.readers.present = true;
    return list;
}

w::Frame authority_request(std::uint16_t opcode, std::uint32_t id, ByteView payload) {
    w::Frame result;
    result.header.kind = w::Kind::request;
    result.header.opcode = opcode;
    result.header.request_id = id;
    result.header.payload_length = static_cast<std::uint32_t>(payload.size);
    result.payload = payload;
    return result;
}

ControlWorkerTask authority_task_for(
    const dtv::userland::detail::AuthorityJob& job) {
    ControlWorkerTask task{};
    task.operation = ControlWorkerOperation::tuner_authority_job;
    task.kind = ControlWorkerTaskKind::authority_request;
    task.authority_job = job;
    task.client_id = job.tune.client_id;
    task.request_id = static_cast<std::uint32_t>(job.key.request_id);
    task.receiver = job.tune.receiver;
    task.lease_id = job.tune.lease_id;
    return task;
}

bool wait_for_completion(ControlWorkerLanes& workers, ControlWorkerLane lane,
                         std::uint32_t request_id, bool* found,
                         TunerAcquireResult* acquire,
                         ControlWorkerCompletion* result = nullptr) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < deadline) {
        ControlWorkerCompletion completion{};
        while (workers.try_pop(lane, completion)) {
            if (completion.request_id == request_id) {
                if (found != nullptr) *found = true;
                if (acquire != nullptr) *acquire = completion.tuner_acquire;
                if (result != nullptr) *result = completion;
                return true;
            }
        }
        pollfd descriptor{workers.wake_fd(), POLLIN, 0};
        (void)::poll(&descriptor, 1U, 10);
        workers.drain_wake();
    }
    return false;
}

bool collect_tune_pair(ControlWorkerLanes& workers, ControlWorkerLane lane,
                       std::uint32_t blocked_id, bool& blocked_found,
                       ControlWorkerCompletion& blocked_completion,
                       std::uint32_t authority_id, bool& authority_found,
                       ControlWorkerCompletion& authority_completion) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < deadline &&
           (!blocked_found || !authority_found)) {
        ControlWorkerCompletion completion{};
        bool drained = false;
        while (workers.try_pop(lane, completion)) {
            drained = true;
            if (completion.request_id == blocked_id) {
                blocked_completion = completion;
                blocked_found = true;
            } else if (completion.request_id == authority_id) {
                authority_completion = completion;
                authority_found = true;
            }
        }
        if (!drained) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return blocked_found && authority_found;
}

int run_authority_tune(bool expect_blocked, bool expect_status_overlap) {
    Backend backend;
    backend.block_receiver0 = false;
    Nonce nonce;
    Time time;
    TunerService tuner(backend, nonce, time);
    if (!tuner.configure_strict_capabilities()) {
        std::fprintf(stderr, "strict authority tune setup failed\n");
        return 1;
    }
    CardBackend card_backend;
    CardSession card_session;
    CardService card(card_backend, card_session);
    ControlExecutionLayout execution{};
    execution.receiver_lane[0] = ControlWorkerLane::tuner_dev1;
    execution.receiver_lane[1] = ControlWorkerLane::tuner_dev2;
    execution.receiver_lane[2] = ControlWorkerLane::tuner_dev1;
    auto created = ControlWorkerLanes::create(card, tuner, nullptr, execution);
    if (!created) return 1;
    auto workers = std::move(created.value());
    TuneUnblockGuard unblock(backend);

    const auto lane0 = workers->authority_lane(0U, false);
    const auto lane2 = workers->authority_lane(2U, false);
    if (!lane0 || !lane2 || lane0.value() != lane2.value()) {
        std::fprintf(stderr, "authority tune receiver mapping mismatch\n");
        return 1;
    }

    const auto list = authority_list();
    w::ServerHello server;
    server.epoch = list.epoch;
    server.producer = list.producer;
    px4::userland::HostAuthorityOwner owner(tuner, server, list);
    auto& authority = owner.authority();
    dtv::userland::detail::ControlServiceSession session(authority);
    AuthoritySessionGuard session_guard(authority, session);
    AuthorityExecutor executor;
    if (authority.open(session) != Error::OK) {
        std::fprintf(stderr, "authority session open failed\n");
        return 1;
    }
    std::array<std::uint8_t, w::kHelloRequestSize> hello_payload{};
    const auto hello_size = w::encode_hello_request(
        {{w::kVersionMajor, w::kVersionMinor},
         {w::kVersionMajor, w::kVersionMinor}, 0U, 0U},
        {hello_payload.data(), hello_payload.size()});
    if (!hello_size) return 1;
    w::Context context;
    w::Snapshot snapshot;
    auto reply = authority.accept(session,
        authority_request(w::kOpcodeHello, 1U,
            {hello_payload.data(), hello_size.value()}),
        context, snapshot, 0U, executor);
    dtv::userland::detail::AuthorityJob rejected;
    if (reply.action != w::InboundAction::respond ||
        authority.response_sent(session, true, executor, &rejected) != Error::OK) {
        std::fprintf(stderr, "authority hello failed\n");
        return 1;
    }

    std::array<std::uint8_t, w::kAcquireRequestSize> acquire_payload{};
    const auto acquire_size = w::encode_acquire_request({server.epoch, 2U},
        {acquire_payload.data(), acquire_payload.size()});
    if (!acquire_size) return 1;
    reply = authority.accept(session,
        authority_request(w::kOpcodeAcquire, 2U,
            {acquire_payload.data(), acquire_size.value()}),
        context, snapshot, 0U, executor);
    if (!reply.queued || !executor.has_job) {
        std::fprintf(stderr, "authority receiver 2 acquire was not queued\n");
        return 1;
    }
    const auto acquire_job = executor.take();
    ControlWorkerTask acquire_task{};
    acquire_task.operation = ControlWorkerOperation::tuner_authority_job;
    acquire_task.kind = ControlWorkerTaskKind::authority_request;
    acquire_task.authority_job = acquire_job;
    acquire_task.client_id = acquire_job.client_id;
    acquire_task.request_id = static_cast<std::uint32_t>(acquire_job.key.request_id);
    acquire_task.receiver = acquire_job.receiver;
    acquire_task.lease_id = acquire_job.lease_id;
    ControlWorkerCompletion acquire_completion{};
    if (!workers->submit(lane2.value(), acquire_task) ||
        !wait_for_completion(*workers, lane2.value(), acquire_task.request_id,
                             nullptr, nullptr, &acquire_completion) ||
        acquire_completion.error != Error::OK) {
        std::fprintf(stderr, "authority receiver 2 acquire did not complete\n");
        return 1;
    }
    reply = authority.complete(acquire_completion.authority_completion);
    if (reply.action != w::InboundAction::respond ||
        authority.response_sent(session, true, executor, &rejected) != Error::OK) {
        std::fprintf(stderr, "authority receiver 2 acquire response failed\n");
        return 1;
    }
    const auto acquire_response = w::decode_acquire_response(
        {reply.frame.data + w::kHeaderSize,
         reply.frame.size - w::kHeaderSize});
    if (!acquire_response) return 1;

    std::array<std::uint8_t, 128U> tune_payload{};
    w::TuneRequest tune_request;
    tune_request.epoch = server.epoch;
    tune_request.lease_id = acquire_response.value().lease_id;
    tune_request.frequency_hz = 473142857U;
    tune_request.timeout_ms = 1000U;
    const auto tune_size = w::encode_tune_request(tune_request,
        {tune_payload.data(), tune_payload.size()});
    if (!tune_size) return 1;
    context.state = w::ContextState::ready;
    reply = authority.accept(session,
        authority_request(w::kOpcodeTune, 3U,
            {tune_payload.data(), tune_size.value()}),
        context, snapshot, 0U, executor);
    if (!reply.queued || !executor.has_job) {
        if (reply.frame.size >= w::kHeaderSize) {
            const auto header = w::decode_header({reply.frame.data, w::kHeaderSize});
            if (header) {
                const auto error = w::decode_error_response(
                    {reply.frame.data + w::kHeaderSize, header.value().payload_length});
                if (error) std::fprintf(stderr, "authority TUNE error=%u\n",
                    static_cast<unsigned>(error.value().error.code));
            }
        }
        std::fprintf(stderr, "authority receiver 2 tune was not admitted\n");
        return 1;
    }
    const auto authority_job = executor.take();
    const auto authority_task = authority_task_for(authority_job);
    dtv::userland::detail::AuthorityJob lnb_job{};
    lnb_job.kind = dtv::userland::detail::AuthorityJobKind::lnb_power;
    lnb_job.key = {authority_job.key.client_id, 4U};
    lnb_job.client_id = authority_job.key.client_id;
    lnb_job.lnb_enabled = true;
    ControlWorkerTask lnb_task{};
    lnb_task.operation = ControlWorkerOperation::tuner_authority_job;
    lnb_task.kind = ControlWorkerTaskKind::authority_request;
    lnb_task.authority_job = lnb_job;
    lnb_task.client_id = lnb_job.client_id;
    lnb_task.request_id = static_cast<std::uint32_t>(lnb_job.key.request_id);
    lnb_task.receiver = lnb_job.receiver;
    const auto lnb_lane = workers->authority_lnb_lane();
    if (!lnb_lane) return 1;

    const auto lease0 = tuner.acquire(101U, 0U);
    if (!lease0) {
        std::fprintf(stderr, "receiver 0 lease setup failed\n");
        return 1;
    }
    backend.block_tune_receiver0 = true;
    ControlWorkerTask blocked_tune{};
    blocked_tune.operation = ControlWorkerOperation::tuner_tune;
    blocked_tune.client_id = 101U;
    blocked_tune.receiver = 0U;
    blocked_tune.lease_id = lease0.value().lease_id;
    blocked_tune.request_id = 10U;
    blocked_tune.tune.lease_id = lease0.value().lease_id;
    blocked_tune.tune.system = ipc::System::ISDB_S;
    blocked_tune.tune.frequency_khz = 1172748U;
    blocked_tune.tune.stream_id = 0xffffU;
    blocked_tune.tune.slot = 0U;
    blocked_tune.tune.timeout_ms = 1000U;
    if (!workers->submit(lane0.value(), blocked_tune) ||
        !backend.wait_tune_receiver0()) {
        std::fprintf(stderr, "receiver 0 did not enter blocked TUNE backend\n");
        return 1;
    }
    if (!workers->submit(lane2.value(), authority_task)) {
        backend.release_tune_receiver0();
        std::fprintf(stderr, "authority receiver 2 TUNE was rejected\n");
        return 1;
    }
    if (!workers->submit(lnb_lane.value(), lnb_task)) {
        backend.release_tune_receiver0();
        std::fprintf(stderr, "shared Authority LNB task was rejected\n");
        return 1;
    }

    bool authority_completed = false;
    bool lnb_completed = false;
    ControlWorkerCompletion authority_completion{};
    ControlWorkerCompletion lnb_completion{};
    const auto progress_deadline = std::chrono::steady_clock::now() +
        (expect_blocked ? std::chrono::milliseconds(300) : std::chrono::seconds(3));
    while (std::chrono::steady_clock::now() < progress_deadline &&
           (!authority_completed || !lnb_completed)) {
        ControlWorkerCompletion completion{};
        while (workers->try_pop(lane0.value(), completion)) {
            if (completion.request_id == authority_task.request_id) {
                authority_completion = completion;
                authority_completed = true;
            } else if (completion.request_id == lnb_task.request_id) {
                lnb_completion = completion;
                lnb_completed = true;
            }
        }
        if (!authority_completed || !lnb_completed)
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    const bool outcome_matches = expect_blocked
        ? !authority_completed && !lnb_completed
        : authority_completed && lnb_completed;
    backend.release_tune_receiver0();
    ControlWorkerCompletion blocked_completion{};
    bool blocked_completed = false;
    const auto completion_deadline = std::chrono::steady_clock::now() +
                                     std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < completion_deadline &&
           (!blocked_completed || !authority_completed || !lnb_completed)) {
        ControlWorkerCompletion completion{};
        bool drained = false;
        while (workers->try_pop(lane0.value(), completion)) {
            drained = true;
            if (completion.request_id == blocked_tune.request_id) {
                blocked_completion = completion;
                blocked_completed = true;
            } else if (completion.request_id == authority_task.request_id) {
                authority_completion = completion;
                authority_completed = true;
            } else if (completion.request_id == lnb_task.request_id) {
                lnb_completion = completion;
                lnb_completed = true;
            }
        }
        if (!drained) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if (!outcome_matches || !blocked_completed || !authority_completed ||
        !lnb_completed ||
        blocked_completion.error != Error::OK ||
        authority_completion.error != Error::OK || lnb_completion.error != Error::OK ||
        !backend.lnb_called.load() || !backend.lnb_enabled.load()) {
        std::fprintf(stderr, "authority TUNE/LNB progress outcome mismatch\n");
        return 1;
    }
    (void)authority.complete(authority_completion.authority_completion);
    if (authority.response_sent(session, true, executor, &rejected) != Error::OK) {
        std::fprintf(stderr, "authority TUNE response completion failed\n");
        return 1;
    }

    if (!workers->stop_and_join()) return 1;
    workers.reset();
    ControlExecutionLayout status_execution{};
    status_execution.receiver_lane.fill(ControlWorkerLane::tuner_dev2);
    status_execution.receiver_lane[2U] = ControlWorkerLane::tuner_dev1;
    status_execution.status_lane = ControlWorkerLane::tuner_dev1;
    auto status_workers_result = ControlWorkerLanes::create(
        card, tuner, nullptr, status_execution);
    if (!status_workers_result) return 1;
    workers = std::move(status_workers_result.value());
    const auto status_receiver_lane = workers->authority_lane(2U, false);
    const auto status_lane = workers->authority_status_lane();
    if (!status_receiver_lane || status_receiver_lane.value() != status_lane) return 1;

    tune_request.lease_id = acquire_response.value().lease_id;
    const auto second_tune_size = w::encode_tune_request(tune_request,
        {tune_payload.data(), tune_payload.size()});
    if (!second_tune_size) return 1;
    reply = authority.accept(session,
        authority_request(w::kOpcodeTune, 5U,
            {tune_payload.data(), second_tune_size.value()}),
        context, snapshot, 0U, executor);
    if (!reply.queued || !executor.has_job) return 1;
    const auto status_tune_job = executor.take();
    const auto status_tune_task = authority_task_for(status_tune_job);
    backend.block_tune_receiver2.store(true);
    if (!workers->submit(status_receiver_lane.value(), status_tune_task) ||
        !backend.wait_blocked_tune_receiver2()) {
        backend.release_tune_receiver2();
        std::fprintf(stderr, "second Authority TUNE did not enter shared command resource\n");
        return 1;
    }
    dtv::userland::detail::AuthorityJob card_status_job{};
    card_status_job.kind = dtv::userland::detail::AuthorityJobKind::internal_card_status;
    card_status_job.key = {status_tune_job.key.client_id, 6U};
    card_status_job.client_id = card_status_job.key.client_id;
    ControlWorkerTask card_status_task{};
    card_status_task.operation = ControlWorkerOperation::tuner_authority_job;
    card_status_task.kind = ControlWorkerTaskKind::authority_request;
    card_status_task.authority_job = card_status_job;
    card_status_task.client_id = card_status_job.client_id;
    card_status_task.request_id = static_cast<std::uint32_t>(card_status_job.key.request_id);
    card_status_task.receiver = card_status_job.receiver;
    if (card_status_task.receiver != 0U ||
        !workers->submit(status_lane, card_status_task)) {
        backend.release_tune_receiver2();
        std::fprintf(stderr, "default-receiver internal card status was not queued\n");
        return 1;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    const bool status_overlapped = backend.status_calls.load() != 0U &&
        backend.status_during_tune.load();
    const bool status_outcome_matches = expect_status_overlap
        ? status_overlapped && backend.status_calls.load() == 1U
        : backend.status_calls.load() == 0U && !status_overlapped;
    backend.release_tune_receiver2();
    bool status_tune_completed = false;
    bool card_status_completed = false;
    ControlWorkerCompletion status_tune_completion{};
    ControlWorkerCompletion card_status_completion{};
    const auto status_deadline = std::chrono::steady_clock::now() +
                                 std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < status_deadline &&
           (!status_tune_completed || !card_status_completed)) {
        ControlWorkerCompletion completion{};
        while (workers->try_pop(status_receiver_lane.value(), completion)) {
            if (completion.request_id == status_tune_task.request_id) {
                status_tune_completion = completion;
                status_tune_completed = true;
            } else if (completion.request_id == card_status_task.request_id) {
                card_status_completion = completion;
                card_status_completed = true;
            }
        }
        if (!status_tune_completed || !card_status_completed)
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if (!status_outcome_matches || !status_tune_completed ||
        !card_status_completed || status_tune_completion.error != Error::OK ||
        card_status_completion.error != Error::OK ||
        backend.status_calls.load() != 1U) {
        std::fprintf(stderr, "status overlap=%d calls=%u expected-blocked=%d tune=%d status=%d\n",
            status_overlapped, backend.status_calls.load(), expect_blocked,
            status_tune_completed, card_status_completed);
        std::fprintf(stderr, "Authority TUNE/internal card status ordering mismatch\n");
        return 1;
    }
    (void)authority.complete(status_tune_completion.authority_completion);
    if (authority.response_sent(session, true, executor, &rejected) != Error::OK) {
        std::fprintf(stderr, "second Authority TUNE response completion failed\n");
        return 1;
    }

    const auto cleanup_job = authority.close(session);
    ControlWorkerTask cleanup_task{};
    cleanup_task.operation = ControlWorkerOperation::tuner_authority_job;
    cleanup_task.kind = ControlWorkerTaskKind::authority_cleanup;
    cleanup_task.authority_job = cleanup_job;
    cleanup_task.client_id = cleanup_job.client_id;
    cleanup_task.request_id = static_cast<std::uint32_t>(cleanup_job.key.request_id);
    cleanup_task.receiver = cleanup_job.receiver;
    cleanup_task.lease_id = cleanup_job.lease_id;
    const auto cleanup_lane = workers->authority_lane(cleanup_job.receiver, true);
    ControlWorkerCompletion cleanup_completion{};
    if (!cleanup_lane || !workers->submit(cleanup_lane.value(), cleanup_task) ||
        !wait_for_completion(*workers, cleanup_lane.value(), cleanup_task.request_id,
                             nullptr, nullptr, &cleanup_completion) ||
        cleanup_completion.error != Error::OK) {
        std::fprintf(stderr, "authority disconnect cleanup did not complete\n");
        return 1;
    }
    if (expect_blocked) {
        std::puts("baseline authority TUNE/LNB blocking reproduced");
    } else {
        std::puts("same-group authority TUNE and shared LNB progress: PASS");
    }
    (void)workers->stop_and_join();
    (void)tuner.disconnect_client(101U);
    return 0;
}

int run(bool expect_blocked) {
    Backend backend;
    Nonce nonce;
    Time time;
    TunerService tuner(backend, nonce, time);
    CardBackend card_backend;
    CardSession card_session;
    CardService card(card_backend, card_session);
    ControlExecutionLayout execution{};
    execution.receiver_lane[1] = ControlWorkerLane::tuner_dev2;
    execution.receiver_lane[2] = ControlWorkerLane::tuner_dev1;
    auto created = ControlWorkerLanes::create(card, tuner, nullptr, execution);
    if (!created) return 1;
    auto workers = std::move(created.value());
    UnblockGuard unblock(backend);

    const auto lane0 = workers->authority_lane(0U, false);
    const auto other_group = workers->authority_lane(1U, false);
    const auto sibling_lane = workers->authority_lane(2U, false);
    if (!lane0 || !other_group || !sibling_lane ||
        lane0.value() == other_group.value() ||
        lane0.value() != sibling_lane.value()) {
        std::fprintf(stderr, "receiver layout is not noncontiguous as expected\n");
        return 1;
    }
    ControlWorkerTask first{};
    first.operation = ControlWorkerOperation::tuner_acquire;
    first.client_id = 101U;
    first.request_id = 1001U;
    first.receiver = 0U;
    if (!workers->submit(lane0.value(), first) || !backend.wait_receiver0()) {
        std::fprintf(stderr, "blocking receiver 0 backend operation did not start\n");
        return 1;
    }
    ControlWorkerTask sibling{};
    sibling.operation = ControlWorkerOperation::tuner_acquire;
    sibling.client_id = 102U;
    sibling.request_id = 1002U;
    sibling.receiver = 2U;
    if (!workers->submit(sibling_lane.value(), sibling)) {
        std::fprintf(stderr, "sibling receiver task was not admitted\n");
        return 1;
    }

    bool sibling_completed = false;
    TunerAcquireResult sibling_result{};
    (void)wait_for_completion(*workers, sibling_lane.value(), sibling.request_id,
                              &sibling_completed, &sibling_result);
    const bool outcome_matches = expect_blocked ? !sibling_completed : sibling_completed;
    backend.release_receiver0();
    bool first_completed = false;
    TunerAcquireResult first_result{};
    (void)wait_for_completion(*workers, lane0.value(), first.request_id,
                              &first_completed, &first_result);
    if (!sibling_completed) {
        (void)wait_for_completion(*workers, sibling_lane.value(), sibling.request_id,
                                  &sibling_completed, &sibling_result);
    }
    if (!first_completed || !sibling_completed || !outcome_matches) {
        std::fprintf(stderr, "same-group sibling completion outcome mismatch\n");
        return 1;
    }
    (void)workers->stop_and_join();
    (void)tuner.disconnect_client(first.client_id);
    (void)tuner.disconnect_client(sibling.client_id);
    std::puts(expect_blocked
        ? "baseline confirmed same-group serialization"
        : "same-group receiver concurrency: PASS");
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::strcmp(argv[1], "--expect-blocked") == 0)
        return run(true) == 0 ? run_authority_tune(true, false) : 1;
    if (argc == 2 && std::strcmp(argv[1], "--expect-status-overlap") == 0)
        return run(false) == 0 ? run_authority_tune(false, true) : 1;
    if (argc == 1)
        return run(false) == 0 ? run_authority_tune(false, false) : 1;
    return 2;
}
