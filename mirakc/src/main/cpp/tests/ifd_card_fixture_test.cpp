#include <dtv/userland/host_listener_context/enclosure_context.h>
#include <dtv/userland/host_runtime/control_socket.h>
#include <px4/tuner_service.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <thread>
#include <unistd.h>

namespace {

using namespace dtv::userland::host_listener_context;
using namespace px4::userland;
namespace host_runtime = dtv::userland::host_runtime;
namespace w = dtv::userland::wire;
namespace detail = dtv::userland::detail;

extern "C" int ifd_fixture_open(const char* runtime, std::uint64_t* handle);
extern "C" int ifd_fixture_transmit(std::uint64_t handle,
                                    const std::uint8_t* apdu,
                                    std::size_t apdu_size,
                                    std::uint8_t* response,
                                    std::size_t* response_size);
extern "C" int ifd_fixture_disconnect(std::uint64_t handle);
extern "C" void ifd_fixture_close();

#if defined(CARD_FIXTURE_ASICEN)
constexpr const char* kProduct = "asicen-userland";
#else
constexpr const char* kProduct = "px4-userland";
#endif
constexpr const char* kInstance = "fixture-one";

template <std::size_t Size>
w::WString<Size> text(const char* value) {
    w::WString<Size> result;
    while (result.length < result.bytes.size() && value[result.length] != '\0') {
        result.bytes[result.length] = value[result.length];
        ++result.length;
    }
    return result;
}

struct Counters final {
    std::atomic<std::size_t> power_on{0U};
    std::atomic<std::size_t> power_off{0U};
    std::atomic<std::size_t> uart_initializations{0U};
    std::atomic<std::size_t> card_initializations{0U};
    std::atomic<std::size_t> transmits{0U};
};

class TunerBackend final : public TunerServiceBackend {
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

class CardBackend final : public CardServiceBackend {
public:
    explicit CardBackend(std::shared_ptr<Counters> counters)
        : counters_(std::move(counters)) {}
    Result<void> set_power(bool on) noexcept override {
        (on ? counters_->power_on : counters_->power_off).fetch_add(1U);
        return Result<void>::success();
    }
    Result<void> initialize_uart() noexcept override {
        counters_->uart_initializations.fetch_add(1U);
        return Result<void>::success();
    }
    Result<bool> detect_card() noexcept override {
        return Result<bool>::success(true);
    }
private:
    std::shared_ptr<Counters> counters_;
};

class CardSession final : public CardProtocolSession {
public:
    explicit CardSession(std::shared_ptr<Counters> counters)
        : counters_(std::move(counters)) {}
    Result<void> initialize() noexcept override {
        initialized_ = true;
        counters_->card_initializations.fetch_add(1U);
        atr_.length = 2U;
        atr_.bytes[0U] = 0x3bU;
        atr_.bytes[1U] = 0x00U;
        return Result<void>::success();
    }
    Result<std::size_t> transmit(ByteView apdu,
                                 MutableByteView response) noexcept override {
        if (!initialized_ || apdu.data == nullptr || apdu.size == 0U ||
            response.data == nullptr || response.size < 3U)
            return Result<std::size_t>::failure(Error::INVALID_ARGUMENT);
        counters_->transmits.fetch_add(1U);
        response.data[0U] = apdu.data[0U];
        response.data[1U] = 0x90U;
        response.data[2U] = 0x00U;
        return Result<std::size_t>::success(3U);
    }
    bool initialized() const noexcept override { return initialized_; }
    const CardAtrBytes& atr() const noexcept override { return atr_; }
    void invalidate() noexcept override {
        initialized_ = false;
        atr_ = CardAtrBytes{};
    }
private:
    std::shared_ptr<Counters> counters_;
    CardAtrBytes atr_{};
    bool initialized_ = false;
};

class Clock final : public TunerServiceTime {
public:
    std::uint64_t monotonic_ms() noexcept override {
        return static_cast<std::uint64_t>(std::chrono::duration_cast<
            std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
    }
    void sleep_ms(std::uint32_t milliseconds) noexcept override {
        std::this_thread::sleep_for(std::chrono::milliseconds(milliseconds));
    }
};

class Nonce final : public TunerNonceSource {
public:
    Result<std::array<std::uint8_t, ipc::kNonceLength>> generate() noexcept override {
        std::array<std::uint8_t, ipc::kNonceLength> value{};
        value[0U] = 1U;
        return Result<std::array<std::uint8_t, ipc::kNonceLength>>::success(value);
    }
};

class Family final : public host_runtime::EnclosureFamily {
public:
    explicit Family(std::shared_ptr<Counters> counters)
        : card_backend_(counters), card_session_(std::move(counters)) {}
    TunerServiceBackend& tuner_backend() noexcept override { return tuner_; }
    CardServiceBackend& card_backend() noexcept override { return card_backend_; }
    CardProtocolSession& card_protocol() noexcept override { return card_session_; }
    TunerServiceTime& time() noexcept override { return clock_; }
    TunerStreamControl* stream_control() noexcept override { return nullptr; }
private:
    TunerBackend tuner_;
    CardBackend card_backend_;
    CardSession card_session_;
    Clock clock_;
};

class TempDirectory final {
public:
    TempDirectory() {
        std::array<char, 40U> path{};
        std::memcpy(path.data(), "/tmp/dtv-card-fixture-XXXXXX", 29U);
        char* created = ::mkdtemp(path.data());
        if (created != nullptr) path_ = created;
    }
    ~TempDirectory() { if (!path_.empty()) std::filesystem::remove_all(path_); }
    const std::string& path() const noexcept { return path_; }
private:
    std::string path_;
};

w::ListResponse make_list(const char* product, const w::Epoch& epoch) {
    w::ListResponse list;
    list.epoch = epoch;
    list.producer.product = text<32U>(product);
    list.producer.version = text<64U>("fixture");
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
    list.enclosure.value.id = 0U;
    list.enclosure.value.identity.serial_state = w::IdentitySerialState::absent;
    list.enclosure.value.identity.uniqueness = w::Uniqueness::not_applicable;
    list.receivers.present = true;
    list.receivers.value.count = 1U;
    list.receivers.value.items[0U].id = 0U;
    list.receivers.value.items[0U].systems.mask = 1U;
    list.receivers.value.items[0U].binding.present = true;
    list.receivers.value.items[0U].binding.value = w::Binding{1U, 0U};
    list.readers.present = true;
    list.readers.value.count = 1U;
    list.readers.value.items[0U].id = 0U;
    list.readers.value.items[0U].binding.present = true;
    list.readers.value.items[0U].binding.value = w::Binding{1U, 0U};
    return list;
}

class Server final {
public:
    Server(std::string runtime, std::shared_ptr<Counters> counters,
           std::uint8_t epoch_byte, const char* metadata_product,
           const char* metadata_instance)
        : runtime_(std::move(runtime)), counters_(std::move(counters)),
          metadata_product_(metadata_product), metadata_instance_(metadata_instance) {
        epoch_.fill(epoch_byte);
    }
    ~Server() { (void)stop(); }

    bool start() {
        auto family = std::unique_ptr<host_runtime::EnclosureFamily>(
            new Family(counters_));
        ContextConfig config;
        config.runtime_directory = runtime_;
        config.product = kProduct;
        config.instance = kInstance;
        config.server.epoch = epoch_;
        config.server.producer.product = text<32U>(metadata_product_.c_str());
        config.server.producer.version = text<64U>("fixture");
        config.server.features = w::kFeatureCardService;
        config.canonical_list = make_list(metadata_product_.c_str(), epoch_);
        if (const auto error = w::validate_list_response(config.canonical_list);
            error != Error::OK) {
            std::fprintf(stderr, "fixture list invalid: %u\n",
                         static_cast<unsigned>(error));
            return false;
        }
        config.context.epoch = epoch_;
        config.context.instance.present = true;
        config.context.instance.value = text<detail::kMaxInstanceBytes>(
            metadata_instance_.c_str());
        config.context.state = w::ContextState::ready;
        config.stream_engine_epoch = 0x43415244U;
        config.stream_arm_budget_ms = 1000U;
        auto assembled = EnclosureContext::assemble(
            std::move(family), std::unique_ptr<host_runtime::TunerNonceSource>(
                new Nonce()), config);
        if (!assembled) {
            std::fprintf(stderr, "fixture assemble failed: %u\n",
                         static_cast<unsigned>(assembled.error()));
            return false;
        }
        if (!assembled.value()->reserve_namespace()) {
            std::fprintf(stderr, "fixture namespace reservation failed\n");
            return false;
        }
        if (!assembled.value()->publish()) {
            std::fprintf(stderr, "fixture publish failed\n");
            return false;
        }
        context_ = std::move(assembled.value());
        serving_.store(true, std::memory_order_release);
        poll_thread_ = std::thread([this] {
            while (serving_.load(std::memory_order_acquire)) {
                const auto result = context_->poll_once(dtv::userland::Timeout{25U});
                if (!result && serving_.load(std::memory_order_acquire)) {
                    poll_errors_.fetch_add(1U);
                    std::fprintf(stderr, "fixture poll failed: %u\n",
                                 static_cast<unsigned>(result.error()));
                }
            }
        });
        return true;
    }

    bool stop() {
        if (!context_) return true;
        serving_.store(false, std::memory_order_release);
        if (poll_thread_.joinable()) poll_thread_.join();
        auto closed = context_->close(dtv::userland::Timeout{10000U});
        if (!closed || closed.value() != CloseState::quiesced) {
            std::fprintf(stderr, "fixture server close failed: %u\n",
                         closed ? 1U : static_cast<unsigned>(closed.error()));
            return false;
        }
        context_.reset();
        if (poll_errors_.load() != 0U) {
            std::fprintf(stderr, "fixture server poll errors: %zu\n",
                         poll_errors_.load());
            return false;
        }
        return true;
    }

private:
    std::string runtime_;
    std::shared_ptr<Counters> counters_;
    std::string metadata_product_;
    std::string metadata_instance_;
    w::Epoch epoch_{};
    std::unique_ptr<EnclosureContext> context_;
    std::atomic<bool> serving_{false};
    std::atomic<std::size_t> poll_errors_{0U};
    std::thread poll_thread_;
};

bool wrong_identity_is_rejected(bool wrong_product) {
    TempDirectory directory;
    if (directory.path().empty()) return false;
    auto counters = std::make_shared<Counters>();
    Server server(directory.path(), counters, 0x41U, kProduct, kInstance);
    if (!server.start()) return false;
    auto transport = host_runtime::connect_control(
        {directory.path(), kProduct, kInstance}, dtv::userland::Timeout{5000U});
    if (!transport) return false;
    w::CardClientOptions options;
    options.reader_id = 0U;
    options.share = w::Share::shared;
    const std::string expected_product = wrong_product ? "wrong-product" : kProduct;
    const std::string expected_instance = wrong_product ? kInstance : "wrong-instance";
    options.expected_product = {
        reinterpret_cast<const std::uint8_t*>(expected_product.data()),
        expected_product.size()};
    options.expected_instance = {
        reinterpret_cast<const std::uint8_t*>(expected_instance.data()),
        expected_instance.size()};
    const auto opened = w::begin_card(*transport.value(), options,
                                      dtv::userland::Timeout{5000U});
    transport.value()->close();
    return !opened && opened.error == Error::NOT_FOUND && server.stop();
}

bool persistent_handle_and_epoch_replacement() {
    TempDirectory directory;
    if (directory.path().empty()) { std::fprintf(stderr, "card fixture temp dir failed\n"); return false; }
    auto first_counters = std::make_shared<Counters>();
    Server first(directory.path(), first_counters, 0x51U, kProduct, kInstance);
    struct ClientCleanup final {
        ~ClientCleanup() { ifd_fixture_close(); }
    } client_cleanup;
    if (!first.start()) { std::fprintf(stderr, "first server start failed\n"); return false; }
    std::uint64_t handle = 0U;
    if (ifd_fixture_open(directory.path().c_str(), &handle) == 0 || handle == 0U) {
        std::fprintf(stderr, "IFD client open failed\n");
        return false;
    }
    std::array<std::uint8_t, 3U> response{};
    const std::array<std::uint8_t, 2U> apdu_a{{0x00U, 0xa4U}};
    const std::array<std::uint8_t, 2U> apdu_b{{0x80U, 0x84U}};
    std::size_t response_size = response.size();
    const int first_response = ifd_fixture_transmit(
        handle, apdu_a.data(), apdu_a.size(), response.data(), &response_size);
    if (first_response == 0 || response_size != 3U || response[0U] != apdu_a[0U]) {
        std::fprintf(stderr, "first APDU failed\n");
        return false;
    }
    const std::size_t power_after_first = first_counters->power_on.load();
    const std::size_t power_off_after_first = first_counters->power_off.load();
    response_size = response.size();
    const int second_response = ifd_fixture_transmit(
        handle, apdu_b.data(), apdu_b.size(), response.data(), &response_size);
    if (second_response == 0 || response_size != 3U || response[0U] != apdu_b[0U] ||
        first_counters->power_on.load() != power_after_first ||
        first_counters->power_off.load() != power_off_after_first ||
        first_counters->card_initializations.load() != 1U ||
        first_counters->transmits.load() != 2U) {
        std::fprintf(stderr, "second APDU/power epoch: rc=%d size=%zu first=%u power=%zu init=%zu transmit=%zu\n",
                     second_response, response_size, response[0U],
                     first_counters->power_on.load(),
                     first_counters->card_initializations.load(),
                     first_counters->transmits.load());
        return false;
    }
    if (!first.stop()) { std::fprintf(stderr, "first server shutdown failed\n"); return false; }

    auto replacement_counters = std::make_shared<Counters>();
    Server replacement(directory.path(), replacement_counters, 0x52U,
                       kProduct, kInstance);
    if (!replacement.start()) { std::fprintf(stderr, "replacement server start failed\n"); return false; }
    response_size = response.size();
    const int stale = ifd_fixture_transmit(
        handle, apdu_a.data(), apdu_a.size(), response.data(), &response_size);
    if (stale != 0 || replacement_counters->transmits.load() != 0U) {
        std::fprintf(stderr, "stale epoch transmit reached replacement\n");
        return false;
    }
    std::uint64_t new_handle = 0U;
    if (ifd_fixture_open(directory.path().c_str(), &new_handle) == 0 || new_handle == 0U) {
        std::fprintf(stderr, "new epoch client open failed\n");
        return false;
    }
    response_size = response.size();
    const int new_response = ifd_fixture_transmit(
        new_handle, apdu_b.data(), apdu_b.size(), response.data(), &response_size);
    if (new_response == 0 || replacement_counters->transmits.load() != 1U) {
        std::fprintf(stderr, "new epoch APDU failed\n");
        return false;
    }
    const int disconnected = ifd_fixture_disconnect(new_handle);
    if (disconnected == 0) { std::fprintf(stderr, "new epoch disconnect failed\n"); return false; }
    if (!replacement.stop()) { std::fprintf(stderr, "replacement server shutdown failed\n"); return false; }
    return true;
}

}  // namespace

int main() {
    if (!wrong_identity_is_rejected(true)) {
        std::fprintf(stderr, "%s client accepted a mismatched product\n", kProduct);
        return 1;
    }
    if (!wrong_identity_is_rejected(false)) {
        std::fprintf(stderr, "%s client accepted a mismatched instance\n", kProduct);
        return 1;
    }
    if (!persistent_handle_and_epoch_replacement()) {
        std::fprintf(stderr, "%s client persistent-card fixture failed\n", kProduct);
        return 1;
    }
    std::printf("%s persistent IFD client/server fixture: PASS\n", kProduct);
    return 0;
}
