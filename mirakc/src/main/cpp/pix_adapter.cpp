#include "adapter_cli.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <unistd.h>

namespace {

bool consume_option(int argc, char** argv, int* index, const char* option,
                    std::string* value) {
    const std::string argument(argv[*index]);
    const std::string name(option);
    if (argument == name) {
        if (*index + 1 >= argc) return false;
        *value = argv[++*index];
        return true;
    }
    const std::string prefix = name + '=';
    if (argument.compare(0, prefix.size(), prefix) == 0 &&
        argument.size() > prefix.size()) {
        *value = argument.substr(prefix.size());
        return true;
    }
    return false;
}

bool valid_instance(const std::string& value) {
    if (value.empty() || value.size() > 80U) return false;
    for (const unsigned char character : value) {
        if (!((character >= 'A' && character <= 'Z') ||
              (character >= 'a' && character <= 'z') ||
              (character >= '0' && character <= '9') || character == '_' ||
              character == '-' || character == '.')) return false;
    }
    return true;
}

int fail(const char* message) {
    std::fprintf(stderr, "pix adapter: %s\n", message);
    return 64;
}

}  // namespace

int main(int argc, char** argv) {
    std::string program;
    std::string instance;
    std::string runtime_dir;
    std::string receiver;
    std::string channel;
    std::string tsid;
    bool have_program = false;
    bool have_instance = false;
    bool have_runtime_dir = false;
    bool have_receiver = false;
    bool have_channel = false;
    bool have_tsid = false;

    for (int index = 1; index < argc; ++index) {
        std::string value;
        if (consume_option(argc, argv, &index, "--ts-program", &value)) {
            if (have_program) return fail("duplicate --ts-program");
            program = value;
            have_program = true;
        } else if (consume_option(argc, argv, &index, "--instance", &value)) {
            if (have_instance) return fail("duplicate --instance");
            instance = value;
            have_instance = true;
        } else if (consume_option(argc, argv, &index, "--runtime-dir", &value)) {
            if (have_runtime_dir) return fail("duplicate --runtime-dir");
            runtime_dir = value;
            have_runtime_dir = true;
        } else if (consume_option(argc, argv, &index, "--receiver", &value)) {
            if (have_receiver) return fail("duplicate --receiver");
            receiver = value;
            have_receiver = true;
        } else if (consume_option(argc, argv, &index, "--channel", &value)) {
            if (have_channel) return fail("duplicate --channel");
            channel = value;
            have_channel = true;
        } else if (consume_option(argc, argv, &index, "--tsid", &value)) {
            if (have_tsid) return fail("duplicate --tsid");
            tsid = value;
            have_tsid = true;
        } else {
            return fail("unknown or incomplete option");
        }
    }

    if (!have_program || !have_instance || !have_runtime_dir ||
        !have_receiver || !have_channel || program.empty() ||
        runtime_dir.empty() || !valid_instance(instance)) {
        return fail("--ts-program, --instance, --runtime-dir, --receiver and --channel are required");
    }
    unsigned int receiver_id = 0U;
    if (!mirakc_adapter::receiver_token(receiver, &receiver_id)) {
        return fail("--receiver must be a canonical decimal receiver ID");
    }

    mirakc_adapter::NormalizedSelection selection;
    if (!mirakc_adapter::normalize_selection(channel, tsid, have_tsid,
                                             &selection)) {
        return fail("--channel or --tsid is not a supported selector");
    }
    std::vector<std::string> child_storage = mirakc_adapter::build_ts_arguments(
        program, instance, receiver, selection, runtime_dir);
    std::vector<char*> child_argv;
    child_argv.reserve(child_storage.size() + 1U);
    for (std::string& argument : child_storage) child_argv.push_back(argument.data());
    child_argv.push_back(nullptr);

    execv(program.c_str(), child_argv.data());
    std::fprintf(stderr, "pix adapter: exec failed: %s\n", std::strerror(errno));
    return 127;
}
