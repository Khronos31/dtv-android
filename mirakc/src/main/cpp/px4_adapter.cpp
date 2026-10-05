#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <optional>
#include <signal.h>
#include <string>
#include <thread>
#include <vector>
#include <sys/wait.h>
#include <unistd.h>
#include <android/log.h>

#include "arib_std_b25.h"
#include "b_cas_card.h"
#include "px4_card_retry.h"
#include "px4_tune_plan.h"
#include "px4_receiver_retry.h"

#include <algorithm>
#include <atomic>
#include <px4/error.h>
#include <px4/pcsc_ifd_adapter.h>

using B25InputObserver = void (*)(void*, std::uint64_t);
extern "C" int b25_stdio_filter_with_card(B_CAS_CARD* bcas,
                                           std::uint64_t* input_bytes,
                                           B25InputObserver observer,
                                           void* observer_context);
namespace {

constexpr char kScanDiagnosticTag[] = "MirakcGRScan";

bool parse_int(const std::string& text, int* value);

bool is_manual_gr_channel(const std::string& value, int* channel) {
    return channel != nullptr && parse_int(value, channel) &&
           *channel >= 13 && *channel <= 62;
}

const char* scan_attempt_stage(int child_exit_code,
                               std::uint64_t input_bytes,
                               std::uint64_t output_bytes) {
    if (child_exit_code == 4) return "busy";
    if (child_exit_code == 5) return "px4-timeout";
    if (child_exit_code == 8) return "stream-integrity";
    if (child_exit_code != 0) return "px4-ts-error";
    if (output_bytes != 0U) return "ts-forwarded";
    if (input_bytes != 0U) return "input-no-output";
    return "no-input";
}

std::uint64_t elapsed_since(std::chrono::steady_clock::time_point started) {
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started)
            .count());
}

void log_scan_event(const char* stage,
                    int channel,
                    int receiver,
                    int attempt,
                    std::uint64_t bytes,
                    std::uint64_t elapsed_ms) {
    __android_log_print(
        ANDROID_LOG_INFO, kScanDiagnosticTag,
        "stage=%s channel=%d receiver=%d attempt=%d bytes=%llu elapsed_ms=%llu",
        stage, channel, receiver, attempt,
        static_cast<unsigned long long>(bytes),
        static_cast<unsigned long long>(elapsed_ms));
}

struct ScanInputEventContext {
    int channel;
    int receiver;
    int attempt;
    std::chrono::steady_clock::time_point started;
    std::atomic<bool>* logged;
};

void log_scan_event_once(std::atomic<bool>* logged,
                         const char* stage,
                         int channel,
                         int receiver,
                         int attempt,
                         std::uint64_t bytes,
                         std::chrono::steady_clock::time_point started) {
    if (logged == nullptr) return;
    bool expected = false;
    if (!logged->compare_exchange_strong(expected, true, std::memory_order_relaxed)) return;
    log_scan_event(stage, channel, receiver, attempt, bytes, elapsed_since(started));
}

void observe_raw_ts(void* opaque, std::uint64_t bytes) {
    auto* context = static_cast<ScanInputEventContext*>(opaque);
    if (context == nullptr) return;
    log_scan_event_once(context->logged, "first-raw-ts", context->channel,
                        context->receiver, context->attempt, bytes, context->started);
}

void log_scan_child_exit(int channel,
                         int receiver,
                         int attempt,
                         int child_exit_code,
                         int b25_result,
                         int filter_result,
                         bool card_error,
                         std::uint64_t input_bytes,
                         std::uint64_t output_bytes,
                         std::uint64_t elapsed_ms) {
    __android_log_print(
        ANDROID_LOG_INFO, kScanDiagnosticTag,
        "stage=child-exit channel=%d receiver=%d attempt=%d child_exitcode=%d b25_result=%d filter_result=%d card_error=%d input_bytes=%llu output_bytes=%llu elapsed_ms=%llu",
        channel, receiver, attempt, child_exit_code, b25_result, filter_result,
        card_error ? 1 : 0,
        static_cast<unsigned long long>(input_bytes),
        static_cast<unsigned long long>(output_bytes),
        static_cast<unsigned long long>(elapsed_ms));
}

void log_scan_result(int channel,
                     int receiver,
                     int attempts,
                     const char* stage,
                     int child_exit_code,
                     int b25_result,
                     int filter_result,
                     std::uint64_t input_bytes,
                     std::uint64_t output_bytes,
                     std::uint64_t elapsed_ms,
                     int busy_retries,
                     int empty_retries,
                     bool card_error) {
    __android_log_print(
        ANDROID_LOG_INFO, kScanDiagnosticTag,
        "stage=%s channel=%d receiver=%d attempts=%d child_exitcode=%d b25_result=%d filter_result=%d input_bytes=%llu output_bytes=%llu elapsed_ms=%llu busy_retries=%d empty_retries=%d card_error=%d",
        stage, channel, receiver, attempts, child_exit_code, b25_result, filter_result,
        static_cast<unsigned long long>(input_bytes),
        static_cast<unsigned long long>(output_bytes),
        static_cast<unsigned long long>(elapsed_ms), busy_retries, empty_retries,
        card_error ? 1 : 0);
}

bool consume_option(int argc, char** argv, int* index, const char* option, std::string* value) {
    const std::string argument(argv[*index]);
    const std::string name(option);
    if (argument == name) {
        if (*index + 1 >= argc) return false;
        *value = argv[++*index];
        return true;
    }
    const std::string prefix = name + '=';
    if (argument.compare(0, prefix.size(), prefix) == 0 && argument.size() > prefix.size()) {
        *value = argument.substr(prefix.size());
        return true;
    }
    return false;
}

bool parse_int(const std::string& text, int* value) {
    if (text.empty()) return false;
    char* end = nullptr;
    errno = 0;
    const long parsed = std::strtol(text.c_str(), &end, 10);
    if (errno != 0 || end == text.c_str() || *end != '\0' || parsed < 0 || parsed > 1000000) {
        return false;
    }
    *value = static_cast<int>(parsed);
    return true;
}

bool valid_instance_token(const std::string& value) {
    if (value.empty() || value.size() > 80) return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return (character >= 'A' && character <= 'Z') ||
            (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') || character == '_' ||
            character == '-' || character == '.';
    });
}

int fail(const char* message) {
    std::fprintf(stderr, "px4 adapter: %s\n", message);
    return 64;
}

bool retryable_card_connect_error(px4::userland::Error error) {
    switch (error) {
    case px4::userland::Error::BUSY:
    case px4::userland::Error::NOT_READY:
    case px4::userland::Error::TIMEOUT:
    case px4::userland::Error::USB_IO:
    case px4::userland::Error::DISCONNECTED:
    case px4::userland::Error::INTERNAL:
        return true;
    default:
        return false;
    }
}

struct Px4CardContext {
    std::unique_ptr<px4::userland::pcsc::IfdCardClient> client;
    std::uint64_t handle = 0;
    bool failed = false;
};

int card_power_on(void* opaque) {
    auto* context = static_cast<Px4CardContext*>(opaque);
    if (context == nullptr || context->client == nullptr) return -1;
    if (context->handle != 0) {
        if (!context->client->disconnect(context->handle)) context->failed = true;
        context->handle = 0;
    }
    std::uint64_t handle = 0;
    const bool connected = px4_adapter::connect_shared_with_retry(
        [&]() { return context->client->connect_shared(); },
        [](px4::userland::Error error) { return retryable_card_connect_error(error); },
        [](unsigned int delay_us) { usleep(delay_us); },
        &handle);
    if (!connected) {
        context->failed = true;
        return -1;
    }
    context->handle = handle;
    return 0;
}

int card_transmit(void* opaque, const std::uint8_t* apdu, int apdu_len,
                  std::uint8_t* response, int response_max) {
    auto* context = static_cast<Px4CardContext*>(opaque);
    if (context == nullptr || context->client == nullptr || context->handle == 0 ||
        apdu == nullptr || apdu_len <= 0 || response == nullptr || response_max <= 0) {
        return -1;
    }
    const auto result = context->client->transmit(
        context->handle,
        px4::userland::ByteView{apdu, static_cast<std::size_t>(apdu_len)},
        px4::userland::MutableByteView{
            response, static_cast<std::size_t>(response_max)});
    if (!result || result.value() > static_cast<std::size_t>(response_max)) {
        context->failed = true;
        return -1;
    }
    return static_cast<int>(result.value());
}

void card_close(void* opaque) {
    auto* context = static_cast<Px4CardContext*>(opaque);
    if (context == nullptr) return;
    if (context->client != nullptr) {
        if (context->handle != 0 && !context->client->disconnect(context->handle)) {
            context->failed = true;
        }
        context->handle = 0;
        context->client->close();
        context->client.reset();
    }
}

int pass_through(int input_fd,
                 std::uint64_t* input_bytes,
                 B25InputObserver observer,
                 void* observer_context) {
    std::uint8_t buffer[64 * 1024];
    while (true) {
        const ssize_t count = read(input_fd, buffer, sizeof(buffer));
        if (count <= 0) break;
        if (input_bytes != nullptr) {
            *input_bytes += static_cast<std::uint64_t>(count);
        }
        if (observer != nullptr && observer_context != nullptr) {
            observer(observer_context, static_cast<std::uint64_t>(count));
        }
        std::size_t offset = 0;
        while (offset < static_cast<std::size_t>(count)) {
            const ssize_t written = write(STDOUT_FILENO, buffer + offset,
                                          static_cast<std::size_t>(count) - offset);
            if (written <= 0) return 1;
            offset += static_cast<std::size_t>(written);
        }
    }
    return 0;
}

int reap_child(pid_t child, int* status) {
    for (int attempt = 0; attempt < 100; ++attempt) {
        const pid_t result = waitpid(child, status, WNOHANG);
        if (result == child) return 0;
        if (result < 0 && errno != EINTR) return -1;
        usleep(10 * 1000);
    }
    kill(child, SIGTERM);
    for (int attempt = 0; attempt < 50; ++attempt) {
        const pid_t result = waitpid(child, status, WNOHANG);
        if (result == child) return 0;
        if (result < 0 && errno != EINTR) return -1;
        usleep(10 * 1000);
    }
    kill(child, SIGKILL);
    while (waitpid(child, status, 0) < 0 && errno == EINTR) {
    }
    return 1;
}

int child_result(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
}

}  // namespace

int main(int argc, char** argv) {
    std::string px4_ts;
    std::string base_serial;
    std::string instance_token;
    std::string model_text;
    std::string receiver_text;
    std::string runtime_dir;
    std::string channel_text;
    std::string tsid_text;
    bool have_px4_ts = false;
    bool have_base_serial = false;
    bool have_instance_token = false;
    bool have_model = false;
    bool have_receiver = false;
    bool have_runtime_dir = false;
    bool have_channel = false;
    bool have_tsid = false;

    for (int index = 1; index < argc; ++index) {
        std::string value;
        if (consume_option(argc, argv, &index, "--px4-ts", &value)) {
            if (have_px4_ts) return fail("duplicate --px4-ts");
            px4_ts = value;
            have_px4_ts = true;
        } else if (consume_option(argc, argv, &index, "--device", &value)) {
            if (have_base_serial) return fail("duplicate --device");
            base_serial = value;
            have_base_serial = true;
        } else if (consume_option(argc, argv, &index, "--instance", &value)) {
            if (have_instance_token) return fail("duplicate --instance");
            instance_token = value;
            have_instance_token = true;
        } else if (consume_option(argc, argv, &index, "--model", &value)) {
            if (have_model) return fail("duplicate --model");
            model_text = value;
            have_model = true;
        } else if (consume_option(argc, argv, &index, "--receiver", &value)) {
            if (have_receiver) return fail("duplicate --receiver");
            receiver_text = value;
            have_receiver = true;
        } else if (consume_option(argc, argv, &index, "--runtime-dir", &value)) {
            if (have_runtime_dir) return fail("duplicate --runtime-dir");
            runtime_dir = value;
            have_runtime_dir = true;
        } else if (consume_option(argc, argv, &index, "--channel", &value)) {
            if (have_channel) return fail("duplicate --channel");
            channel_text = value;
            have_channel = true;
        } else if (consume_option(argc, argv, &index, "--tsid", &value)) {
            if (have_tsid) return fail("duplicate --tsid");
            tsid_text = value;
            have_tsid = true;
        } else {
            return fail("unknown or incomplete option");
        }
    }

    if (!have_px4_ts || !have_base_serial || !have_instance_token || !have_model || !have_receiver || !have_runtime_dir || !have_channel ||
        px4_ts.empty() || base_serial.empty() || runtime_dir.empty()) {
        return fail("--px4-ts, --device, --instance, --model, --receiver, --runtime-dir and --channel are required");
    }
    if (!valid_instance_token(instance_token)) return fail("--instance is not a valid runtime token");
    int receiver = 0;
    if (!parse_int(receiver_text, &receiver) || receiver_text != std::to_string(receiver)) {
        return fail("--receiver must be a receiver ID");
    }
    const std::optional<px4_adapter::ReceiverMap> parsed_receiver_map =
        px4_adapter::receiver_map_for_model(model_text);
    if (!parsed_receiver_map.has_value()) return fail("--model is not a supported PX4 profile");
    const px4_adapter::ReceiverMap receiver_map = *parsed_receiver_map;
    if (!px4_adapter::base_serial_matches_model(receiver_map, base_serial)) {
        return fail("--device serial length does not match --model");
    }

    px4_adapter::TunePlan tune_plan;
    std::string tune_error;
    const std::optional<std::string_view> tsid = have_tsid
        ? std::optional<std::string_view>(tsid_text)
        : std::nullopt;
    if (!px4_adapter::create_tune_plan(
            receiver, channel_text, tsid, &tune_plan, &tune_error, receiver_map)) {
        return fail(tune_error.c_str());
    }
    const std::string frequency = std::to_string(tune_plan.frequency_khz);
    const std::string system = tune_plan.system == px4_adapter::BroadcastSystem::kIsdbT
        ? "isdb-t"
        : "isdb-s";
    std::vector<std::string> child_storage;
    child_storage.reserve(18);
    child_storage.push_back(px4_ts);
    child_storage.emplace_back("--instance");
    child_storage.push_back(instance_token);
    child_storage.emplace_back("--receiver");
    child_storage.push_back(receiver_text);
    child_storage.emplace_back("--system");
    child_storage.push_back(system);
    child_storage.emplace_back("--frequency-khz");
    child_storage.push_back(frequency);
    if (tune_plan.satellite_selector == px4_adapter::SatelliteSelector::kStreamId) {
        child_storage.emplace_back("--stream-id");
        child_storage.push_back(std::to_string(tune_plan.satellite_value));
    } else if (tune_plan.satellite_selector == px4_adapter::SatelliteSelector::kSlot) {
        child_storage.emplace_back("--slot");
        child_storage.push_back(std::to_string(tune_plan.satellite_value));
    }
    if (tune_plan.system == px4_adapter::BroadcastSystem::kIsdbS) {
        child_storage.emplace_back("--lnb-voltage");
        child_storage.emplace_back("0");
    }
    child_storage.emplace_back("--runtime-dir");
    child_storage.push_back(runtime_dir);
    child_storage.emplace_back("--output");
    child_storage.emplace_back("-");

    px4::userland::pcsc::PosixIfdCardClientFactory factory;
    px4::userland::pcsc::IfdEndpoint endpoint;
    endpoint.runtime_directory = runtime_dir;
    endpoint.device_instance = instance_token;

    // Keep the real downstream (mirakc) endpoint separate: each attempt wraps
    // STDOUT with a counting forwarder so a tune that produces no TS can be
    // detected and retried.
    const int real_stdout = dup(STDOUT_FILENO);
    if (real_stdout < 0) return fail("cannot dup stdout");
    int scan_channel = 0;
    const bool log_scan = tune_plan.system == px4_adapter::BroadcastSystem::kIsdbT &&
                          is_manual_gr_channel(channel_text, &scan_channel);
    const auto scan_started = std::chrono::steady_clock::now();
    std::atomic<bool> raw_ts_logged{false};
    std::atomic<bool> downstream_ts_logged{false};
    std::uint64_t input_bytes_total = 0U;
    std::uint64_t output_bytes_total = 0U;
    int busy_retries = 0;
    int empty_retries = 0;
    bool card_error_seen = false;
    if (log_scan) {
        log_scan_event("started", scan_channel, receiver, 0, 0U, 0U);
    }

    // The previous mirakc tuner session on the same receiver can still hold
    // the px4d lease for a short while after mirakc stops it.  px4-ts then
    // exits with BUSY (exit code 4) before producing any TS.  Even after the
    // lease drains, the first tune attempt can race the receiver reset and
    // end with a clean but empty stream, so retry both cases.
    for (int attempt = 0; attempt < 50; ++attempt) {
        if (!px4_adapter::set_child_receiver_for_attempt(
                &child_storage, receiver_map, tune_plan.system, receiver,
                static_cast<std::size_t>(attempt))) {
            return fail("cannot select child receiver for retry");
        }
        std::vector<char*> child_argv;
        child_argv.reserve(child_storage.size() + 1);
        for (std::string& argument : child_storage) {
            child_argv.push_back(argument.data());
        }
        child_argv.push_back(nullptr);

        int output_pipe[2] = {-1, -1};
        if (pipe2(output_pipe, O_CLOEXEC) != 0) return fail("cannot create TS pipe");
        const pid_t child = fork();
        if (child < 0) {
            close(output_pipe[0]);
            close(output_pipe[1]);
            return fail("cannot fork px4-ts");
        }

        if (child == 0) {
            if (output_pipe[0] != STDIN_FILENO) close(output_pipe[0]);
            if (output_pipe[1] != STDOUT_FILENO) {
                if (dup2(output_pipe[1], STDOUT_FILENO) < 0) _exit(127);
                close(output_pipe[1]);
            }
            execv(px4_ts.c_str(), child_argv.data());
            std::fprintf(stderr, "px4 adapter: exec failed: %s\n", std::strerror(errno));
            _exit(127);
        }
        close(output_pipe[1]);

        int count_pipe[2] = {-1, -1};
        if (pipe2(count_pipe, O_CLOEXEC) != 0) {
            close(output_pipe[0]);
            reap_child(child, nullptr);
            return fail("cannot create count pipe");
        }
        std::uint64_t forwarded = 0;
        std::thread copier([&]() {
            std::uint8_t buffer[64 * 1024];
            while (true) {
                const ssize_t count = read(count_pipe[0], buffer, sizeof(buffer));
                if (count <= 0) break;
                std::size_t offset = 0;
                while (offset < static_cast<std::size_t>(count)) {
                    const ssize_t written = write(real_stdout, buffer + offset,
                                                  static_cast<std::size_t>(count) - offset);
                    if (written <= 0) {
                        close(count_pipe[0]);
                        return;
                    }
                    if (log_scan) {
                        log_scan_event_once(
                            &downstream_ts_logged, "first-downstream-ts", scan_channel,
                            receiver, attempt + 1,
                            static_cast<std::uint64_t>(written), scan_started);
                    }
                    offset += static_cast<std::size_t>(written);
                    forwarded += static_cast<std::uint64_t>(written);
                }
            }
            close(count_pipe[0]);
        });
        if (dup2(count_pipe[1], STDOUT_FILENO) < 0) {
            close(count_pipe[0]);
            close(count_pipe[1]);
            close(output_pipe[0]);
            copier.join();
            reap_child(child, nullptr);
            return 1;
        }
        close(count_pipe[1]);

        int exit_code = 1;
        int child_exit_code = 1;
        int filter_result = 0;
        int b25_result = -1;
        std::uint64_t input_bytes = 0U;
        bool card_error = false;
        ScanInputEventContext input_event_context{
            scan_channel, receiver, attempt + 1, scan_started,
            log_scan ? &raw_ts_logged : nullptr};
        auto client = factory.connect(endpoint);
        if (!client) {
            filter_result = pass_through(
                output_pipe[0], &input_bytes, log_scan ? observe_raw_ts : nullptr,
                log_scan ? &input_event_context : nullptr);
            close(output_pipe[0]);
            int child_status = 0;
            const int reap_result = reap_child(child, &child_status);
            if (reap_result < 0) return 1;
            child_exit_code = child_result(child_status);
            exit_code = filter_result != 0 ? filter_result : child_exit_code;
        } else {
            Px4CardContext card;
            card.client = std::move(client.value());
            const B_CAS_TRANSPORT transport{
                &card, card_power_on, card_transmit, card_close};
            B_CAS_CARD* bcas = create_b_cas_card_with_transport(&transport);
            // After the first attempt, fds 0 and 1 are closed, so pipe2 can
            // return STDIN_FILENO as the pipe read end.  dup2 would be a
            // no-op in that case and closing output_pipe[0] would close the
            // b25 input itself, so skip both when they are the same fd.
            if (output_pipe[0] != STDIN_FILENO) {
                if (dup2(output_pipe[0], STDIN_FILENO) < 0) {
                    if (bcas != nullptr) bcas->release(bcas);
                    else card_close(&card);
                    close(output_pipe[0]);
                    int child_status = 0;
                    reap_child(child, &child_status);
                    return 1;
                }
                close(output_pipe[0]);
            }
            b25_result = b25_stdio_filter_with_card(
                bcas, &input_bytes, log_scan ? observe_raw_ts : nullptr,
                log_scan ? &input_event_context : nullptr);
            filter_result = b25_result;
            card_error = card.failed;
            if (bcas == nullptr) card_close(&card);
            // Closing the duplicated read end is required before waiting:
            // otherwise a failed downstream write can leave px4-ts blocked on
            // a full pipe.
            close(STDIN_FILENO);
            int child_status = 0;
            const int reap_result = reap_child(child, &child_status);
            if (reap_result < 0) return 1;
            child_exit_code = child_result(child_status);
            exit_code = filter_result != 0 ? filter_result
                                           : (card.failed ? 1 : child_exit_code);
        }

        close(STDOUT_FILENO);
        copier.join();
        if (log_scan) {
            log_scan_child_exit(scan_channel, receiver, attempt + 1,
                                child_exit_code, b25_result, filter_result,
                                card_error, input_bytes, forwarded,
                                elapsed_since(scan_started));
        }
        // Keep fds 0 and 1 valid so the next attempt's pipe2 never returns
        // STDIN or STDOUT as a pipe endpoint.
        const int null_fd = open("/dev/null", O_RDWR);
        if (null_fd >= 0) {
            if (null_fd != STDIN_FILENO) dup2(null_fd, STDIN_FILENO);
            if (null_fd != STDOUT_FILENO) dup2(null_fd, STDOUT_FILENO);
            if (null_fd > STDOUT_FILENO) close(null_fd);
        }

        input_bytes_total += input_bytes;
        output_bytes_total += forwarded;
        card_error_seen = card_error_seen || card_error;
        const bool tune_failed = exit_code == 0 && forwarded == 0;
        const bool busy = exit_code == 4;
        const bool exhausted = attempt == 49 && (busy || tune_failed);
        const bool finished = (exit_code != 4 && !tune_failed) || attempt == 49;
        if (busy && !exhausted) ++busy_retries;
        if (tune_failed && !exhausted) ++empty_retries;
        if (finished && log_scan) {
            const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - scan_started).count();
            log_scan_result(
                scan_channel, receiver, attempt + 1,
                scan_attempt_stage(child_exit_code, input_bytes_total,
                                   output_bytes_total),
                child_exit_code, b25_result, filter_result,
                input_bytes_total, output_bytes_total,
                static_cast<std::uint64_t>(elapsed), busy_retries, empty_retries,
                card_error_seen);
        }
        if (finished) return exit_code;
        usleep(500 * 1000);
    }
    return 1;
}
