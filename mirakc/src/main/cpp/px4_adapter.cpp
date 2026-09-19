#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>

namespace {

constexpr int frequency_khz(int channel) {
    return 395142 + channel * 6000;
}

static_assert(frequency_khz(13) == 473142, "GR channel 13 frequency changed");
static_assert(frequency_khz(27) == 557142, "GR channel 27 frequency changed");

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

bool valid_receiver(int receiver) {
    return receiver == 2 || receiver == 3 || receiver == 6 || receiver == 7;
}

int fail(const char* message) {
    std::fprintf(stderr, "px4 adapter: %s\n", message);
    return 64;
}

}  // namespace

int main(int argc, char** argv) {
    std::string px4_ts;
    std::string base_serial;
    std::string receiver_text;
    std::string runtime_dir;
    std::string channel_text;
    bool have_px4_ts = false;
    bool have_base_serial = false;
    bool have_receiver = false;
    bool have_runtime_dir = false;
    bool have_channel = false;

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
        } else {
            return fail("unknown or incomplete option");
        }
    }

    if (!have_px4_ts || !have_base_serial || !have_receiver || !have_runtime_dir || !have_channel ||
        px4_ts.empty() || base_serial.empty() || runtime_dir.empty()) {
        return fail("--px4-ts, --device, --receiver, --runtime-dir and --channel are required");
    }
    int receiver = 0;
    int channel = 0;
    if (!parse_int(receiver_text, &receiver) || !valid_receiver(receiver)) {
        return fail("--receiver must be one of 2, 3, 6 or 7");
    }
    if (!parse_int(channel_text, &channel) || channel < 13 || channel > 62) {
        return fail("--channel must be in the GR range 13..62");
    }

    const std::string frequency = std::to_string(frequency_khz(channel));
    char* const child_argv[] = {
        const_cast<char*>(px4_ts.c_str()),
        const_cast<char*>("--device"),
        const_cast<char*>(base_serial.c_str()),
        const_cast<char*>("--receiver"),
        const_cast<char*>(receiver_text.c_str()),
        const_cast<char*>("--system"),
        const_cast<char*>("isdb-t"),
        const_cast<char*>("--frequency-khz"),
        const_cast<char*>(frequency.c_str()),
        const_cast<char*>("--runtime-dir"),
        const_cast<char*>(runtime_dir.c_str()),
        const_cast<char*>("--output"),
        const_cast<char*>("-"),
        nullptr,
    };
    execv(px4_ts.c_str(), child_argv);
    std::fprintf(stderr, "px4 adapter: exec %s: %s\n", px4_ts.c_str(), std::strerror(errno));
    return 127;
}
