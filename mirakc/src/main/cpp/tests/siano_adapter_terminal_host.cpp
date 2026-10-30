#include <cerrno>
#include <csignal>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <string>
#include <sys/socket.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

#include "../siano_stream_protocol.h"

int siano_adapter_stream_main(int fd);

int __android_log_print(int, const char*, const char*, ...) { return 0; }

namespace {

constexpr char kTsBytes[]{0x47, 0x00, 0x00, 0x10};

struct Result final {
    int code;
    std::string output;
    long elapsed_ms = 0;
};

bool write_all(int fd, const unsigned char* bytes, std::size_t size) {
    constexpr std::size_t chunks[]{1, 2, 5, 3, 8192};
    std::size_t offset = 0;
    std::size_t chunk_index = 0;
    while (offset < size) {
        const std::size_t amount = std::min(chunks[chunk_index++ % 5], size - offset);
        const ssize_t written = ::write(fd, bytes + offset, amount);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        offset += static_cast<std::size_t>(written);
    }
    return true;
}

std::vector<unsigned char> read_hex(const std::string& path) {
    std::ifstream input(path);
    std::vector<unsigned char> bytes;
    std::string token;
    while (input >> token) {
        char* end = nullptr;
        const long value = std::strtol(token.c_str(), &end, 16);
        if (end == token.c_str() || *end != '\0' || value < 0 || value > 255) return {};
        bytes.push_back(static_cast<unsigned char>(value));
    }
    return bytes;
}

void set_u32(std::vector<unsigned char>* bytes, std::size_t offset, std::uint32_t value) {
    for (std::size_t index = 0; index < 4; ++index) {
        (*bytes)[offset + index] = static_cast<unsigned char>(value >> (24U - 8U * index));
    }
}

Result run_case(const std::vector<unsigned char>& bytes) {
    int sockets[2];
    int output[2];
    if (::socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) != 0 || ::pipe(output) != 0) return {-1, {}};
    const pid_t child = ::fork();
    if (child < 0) return {-1, {}};
    if (child == 0) {
        ::close(sockets[0]);
        ::close(output[0]);
        if (::dup2(output[1], STDOUT_FILENO) < 0) _exit(120);
        ::close(output[1]);
        const int status = siano_adapter_stream_main(sockets[1]);
        _exit(status);
    }
    ::close(sockets[1]);
    ::close(output[1]);
    std::thread writer([&] {
        (void)write_all(sockets[0], bytes.data(), bytes.size());
        ::shutdown(sockets[0], SHUT_WR);
        ::close(sockets[0]);
    });
    std::string emitted;
    char buffer[256];
    while (true) {
        const ssize_t count = ::read(output[0], buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        emitted.append(buffer, static_cast<std::size_t>(count));
    }
    ::close(output[0]);
    writer.join();
    int status = 0;
    while (::waitpid(child, &status, 0) < 0 && errno == EINTR) { }
    return {WIFEXITED(status) ? WEXITSTATUS(status) : -1, emitted};
}

Result run_case_with_open_peer(const std::vector<unsigned char>& bytes) {
    int sockets[2];
    int output[2];
    if (::socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) != 0) return {-1, {}};
    if (::pipe(output) != 0) {
        ::close(sockets[0]);
        ::close(sockets[1]);
        return {-1, {}};
    }
    const pid_t child = ::fork();
    if (child < 0) {
        ::close(sockets[0]);
        ::close(sockets[1]);
        ::close(output[0]);
        ::close(output[1]);
        return {-1, {}};
    }
    if (child == 0) {
        ::close(sockets[0]);
        ::close(output[0]);
        if (::dup2(output[1], STDOUT_FILENO) < 0) _exit(120);
        ::close(output[1]);
        const int status = siano_adapter_stream_main(sockets[1]);
        _exit(status);
    }
    ::close(sockets[1]);
    ::close(output[1]);
    if (!write_all(sockets[0], bytes.data(), bytes.size())) {
        ::close(sockets[0]);
        ::close(output[0]);
        ::kill(child, SIGKILL);
        while (::waitpid(child, nullptr, 0) < 0 && errno == EINTR) { }
        return {-1, {}};
    }
    const auto started = std::chrono::steady_clock::now();
    const auto deadline = started + std::chrono::seconds(2);
    int status = 0;
    bool reaped = false;
    while (std::chrono::steady_clock::now() < deadline) {
        const pid_t waited = ::waitpid(child, &status, WNOHANG);
        if (waited == child) {
            reaped = true;
            break;
        }
        if (waited < 0 && errno != EINTR) break;
        ::usleep(5 * 1000);
    }
    if (!reaped) {
        ::kill(child, SIGKILL);
        while (::waitpid(child, &status, 0) < 0 && errno == EINTR) { }
    }
    ::close(sockets[0]);
    std::string emitted;
    char buffer[256];
    while (true) {
        const ssize_t count = ::read(output[0], buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        emitted.append(buffer, static_cast<std::size_t>(count));
    }
    ::close(output[0]);
    const long elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    return {reaped && WIFEXITED(status) ? WEXITSTATUS(status) : -1, emitted, elapsed_ms};
}

bool expect(bool condition, const char* label) {
    if (condition) return true;
    std::fprintf(stderr, "Siano terminal host test failed: %s\n", label);
    return false;
}

int self_test(const std::string& fixture_dir) {
    const auto exit0 = read_hex(fixture_dir + "/exit0.hex");
    const auto exit7 = read_hex(fixture_dir + "/exit7.hex");
    const auto signal9 = read_hex(fixture_dir + "/signal9.hex");
    const std::string expected(kTsBytes, sizeof(kTsBytes));
    if (exit0.empty() || exit7.empty() || signal9.empty()) return 2;
    bool ok = true;
    const Result normal = run_case(exit0);
    ok &= expect(normal.code == 0 && normal.output == expected, "exit 0 preserves DATA and succeeds");
    const Result nonzero = run_case(exit7);
    ok &= expect(nonzero.code == 7 && nonzero.output == expected, "exit 7 preserves DATA and exit status");
    const Result signaled = run_case(signal9);
    ok &= expect(signaled.code == 137 && signaled.output == expected, "signal status is nonzero");
    const Result open_peer = run_case_with_open_peer(exit0);
    ok &= expect(open_peer.code == 1 && open_peer.output == expected && open_peer.elapsed_ms < 1000,
                 "END with an open peer fails within the terminal EOF deadline");

    auto invalid = exit0;
    invalid[0] = 'X';
    ok &= expect(run_case(invalid).code != 0, "invalid magic is rejected");
    invalid = exit0;
    set_u32(&invalid, 4, 3);
    ok &= expect(run_case(invalid).code != 0, "invalid frame type is rejected");
    invalid = exit0;
    set_u32(&invalid, 8, mirakc::siano_stream::kMaxDataBytes + 1U);
    ok &= expect(run_case(invalid).code != 0, "oversized DATA is rejected before allocation");
    invalid = exit0;
    set_u32(&invalid, 8, 0);
    ok &= expect(run_case(invalid).code != 0, "empty DATA is rejected");
    invalid = exit0;
    set_u32(&invalid, 24, 7);
    ok &= expect(run_case(invalid).code != 0, "invalid END length is rejected");
    invalid = exit0;
    set_u32(&invalid, 28, 99);
    ok &= expect(run_case(invalid).code != 0, "invalid terminal outcome is rejected");
    invalid = exit0;
    set_u32(&invalid, 28, static_cast<std::uint32_t>(mirakc::siano_stream::Outcome::exit));
    set_u32(&invalid, 32, 256);
    ok &= expect(run_case(invalid).code != 0, "out-of-range exit status is rejected");
    invalid = exit0;
    set_u32(&invalid, 28, static_cast<std::uint32_t>(mirakc::siano_stream::Outcome::signal));
    set_u32(&invalid, 32, 0);
    ok &= expect(run_case(invalid).code != 0, "zero signal is rejected");
    invalid = exit0;
    set_u32(&invalid, 28, static_cast<std::uint32_t>(mirakc::siano_stream::Outcome::timeout));
    set_u32(&invalid, 32, 1);
    ok &= expect(run_case(invalid).code != 0, "nonzero timeout detail is rejected");
    invalid = exit0;
    invalid.resize(invalid.size() - 4);
    ok &= expect(run_case(invalid).code != 0, "truncated END is rejected");
    invalid = exit0;
    invalid.resize(8);
    ok &= expect(run_case(invalid).code != 0, "truncated header is rejected");
    invalid = exit0;
    invalid.resize(14);
    ok &= expect(run_case(invalid).code != 0, "truncated DATA payload is rejected");
    invalid = exit0;
    invalid.resize(16);
    ok &= expect(run_case(invalid).code != 0, "missing END is rejected");
    invalid = exit0;
    invalid.push_back(0);
    ok &= expect(run_case(invalid).code != 0, "bytes after END are rejected");
    if (!ok) return 1;
    std::puts("Siano terminal status host fixtures: PASS");
    return 0;
}

int bridge_stdin_to_adapter() {
    int sockets[2];
    if (::socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) != 0) return 2;
    std::thread writer([&] {
        unsigned char buffer[8192];
        while (true) {
            const ssize_t count = ::read(STDIN_FILENO, buffer, sizeof(buffer));
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) break;
            if (!write_all(sockets[0], buffer, static_cast<std::size_t>(count))) break;
        }
        ::shutdown(sockets[0], SHUT_WR);
        ::close(sockets[0]);
    });
    const int status = siano_adapter_stream_main(sockets[1]);
    ::close(sockets[1]);
    writer.join();
    return status;
}

}  // namespace

int main(int argc, char** argv) {
    std::signal(SIGPIPE, SIG_IGN);
    if (argc == 3 && std::strcmp(argv[1], "--self-test") == 0) return self_test(argv[2]);
    if (argc == 1) return bridge_stdin_to_adapter();
    return 64;
}
