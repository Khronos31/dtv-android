#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <optional>
#include <signal.h>
#include <string>
#include <vector>
#include <sys/wait.h>
#include <unistd.h>

#include "arib_std_b25.h"
#include "b_cas_card.h"
#include "px4_card_retry.h"
#include "px4_tune_plan.h"

#include <px4/error.h>
#include <px4/pcsc_ifd_adapter.h>

extern "C" int b25_stdio_filter_with_card(B_CAS_CARD* bcas);
namespace {

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

int pass_through(int input_fd) {
    std::uint8_t buffer[64 * 1024];
    while (true) {
        const ssize_t count = read(input_fd, buffer, sizeof(buffer));
        if (count <= 0) break;
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
    std::string receiver_text;
    std::string runtime_dir;
    std::string channel_text;
    std::string tsid_text;
    bool have_px4_ts = false;
    bool have_base_serial = false;
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

    if (!have_px4_ts || !have_base_serial || !have_receiver || !have_runtime_dir || !have_channel ||
        px4_ts.empty() || base_serial.empty() || runtime_dir.empty()) {
        return fail("--px4-ts, --device, --receiver, --runtime-dir and --channel are required");
    }
    int receiver = 0;
    if (!parse_int(receiver_text, &receiver) || receiver_text != std::to_string(receiver)) {
        return fail("--receiver must be a PX-Q3U4 receiver ID");
    }

    px4_adapter::TunePlan tune_plan;
    std::string tune_error;
    const std::optional<std::string_view> tsid = have_tsid
        ? std::optional<std::string_view>(tsid_text)
        : std::nullopt;
    if (!px4_adapter::create_tune_plan(
            receiver, channel_text, tsid, &tune_plan, &tune_error)) {
        return fail(tune_error.c_str());
    }
    const std::string frequency = std::to_string(tune_plan.frequency_khz);
    const std::string system = tune_plan.system == px4_adapter::BroadcastSystem::kIsdbT
        ? "isdb-t"
        : "isdb-s";
    std::vector<std::string> child_storage;
    child_storage.reserve(18);
    child_storage.push_back(px4_ts);
    child_storage.emplace_back("--device");
    child_storage.push_back(base_serial);
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
        close(output_pipe[0]);
        if (dup2(output_pipe[1], STDOUT_FILENO) < 0) _exit(127);
        close(output_pipe[1]);
        execv(px4_ts.c_str(), child_argv.data());
        std::fprintf(stderr, "px4 adapter: exec failed: %s\n", std::strerror(errno));
        _exit(127);
    }
    close(output_pipe[1]);

    px4::userland::pcsc::PosixIfdCardClientFactory factory;
    px4::userland::pcsc::IfdEndpoint endpoint;
    endpoint.runtime_directory = runtime_dir;
    endpoint.device_instance = base_serial;
    auto client = factory.connect(endpoint);
    if (!client) {
        const int result = pass_through(output_pipe[0]);
        close(output_pipe[0]);
        int child_status = 0;
        const int reap_result = reap_child(child, &child_status);
        if (reap_result < 0) return 1;
        return result != 0 ? result : child_result(child_status);
    }

    Px4CardContext card;
    card.client = std::move(client.value());
    const B_CAS_TRANSPORT transport{
        &card, card_power_on, card_transmit, card_close};
    B_CAS_CARD* bcas = create_b_cas_card_with_transport(&transport);
    if (dup2(output_pipe[0], STDIN_FILENO) < 0) {
        if (bcas != nullptr) bcas->release(bcas);
        else card_close(&card);
        close(output_pipe[0]);
        int child_status = 0;
        reap_child(child, &child_status);
        return 1;
    }
    close(output_pipe[0]);
    const int result = b25_stdio_filter_with_card(bcas);
    if (bcas == nullptr) card_close(&card);
    // Closing the duplicated read end is required before waiting: otherwise
    // a failed downstream write can leave px4-ts blocked on a full pipe.
    close(STDIN_FILENO);
    int child_status = 0;
    const int reap_result = reap_child(child, &child_status);
    if (reap_result < 0) return 1;
    if (result != 0 || card.failed) return result != 0 ? result : 1;
    return child_result(child_status);
}
