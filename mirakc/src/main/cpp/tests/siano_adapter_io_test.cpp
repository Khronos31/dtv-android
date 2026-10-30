#include <cerrno>
#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "../siano_stream_protocol.h"

int siano_adapter_main_entry(int argc, char** argv);

namespace {

enum Scenario { ReadError, OutputError, TruncatedHeader, InvalidMagic, InterruptedReadAndShortWrites };
Scenario scenario = ReadError;
int read_calls = 0;
int stdout_write_calls = 0;
std::size_t input_offset = 0;
std::string input;
std::string output;

void append_u32(std::string* bytes, std::uint32_t value) {
    bytes->push_back(static_cast<char>(value >> 24U));
    bytes->push_back(static_cast<char>(value >> 16U));
    bytes->push_back(static_cast<char>(value >> 8U));
    bytes->push_back(static_cast<char>(value));
}

std::string frame(std::uint32_t type, const std::string& payload) {
    std::string result("SIA2", 4);
    append_u32(&result, type);
    append_u32(&result, static_cast<std::uint32_t>(payload.size()));
    result += payload;
    return result;
}

std::string valid_stream() {
    std::string end;
    append_u32(&end, static_cast<std::uint32_t>(mirakc::siano_stream::Outcome::exit));
    append_u32(&end, 0);
    return frame(mirakc::siano_stream::kData, "hello") +
        frame(mirakc::siano_stream::kEnd, end);
}

bool expect(bool condition, const char* label) {
    if (condition) return true;
    std::fprintf(stderr, "Siano adapter I/O test failed: %s\n", label);
    return false;
}

int run(Scenario next) {
    scenario = next;
    read_calls = 0;
    stdout_write_calls = 0;
    input_offset = 0;
    output.clear();
    input = valid_stream();
    if (scenario == TruncatedHeader) input = "SIA2";
    if (scenario == InvalidMagic) input[0] = 'X';
    const std::string name = "siano-io-test-" + std::to_string(::getpid()) + "-" + std::to_string(next);
    const int server = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) return -1;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, name.data(), name.size());
    const socklen_t length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
    if (::bind(server, reinterpret_cast<sockaddr*>(&address), length) != 0 || ::listen(server, 1) != 0) {
        ::close(server);
        return -1;
    }
    std::thread tuner([server] {
        const int client = ::accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        if (client >= 0) {
            char request[256];
            (void)::read(client, request, sizeof(request));
            ::close(client);
        }
        ::close(server);
    });
    const std::string socket_option = "--socket=@" + name;
    char token_option[] = "--token=fixture";
    char index_option[] = "--tuner-index=0";
    char channel_option[] = "--channel=13";
    char program[] = "siano-adapter";
    char* argv[] = {program, const_cast<char*>(socket_option.c_str()), token_option,
                    index_option, channel_option, nullptr};
    const int result = siano_adapter_main_entry(5, argv);
    tuner.join();
    return result;
}

}  // namespace

extern "C" ssize_t host_read(int, void* buffer, size_t size) {
    ++read_calls;
    if ((scenario == ReadError && read_calls == 1) ||
        (scenario == InterruptedReadAndShortWrites && read_calls == 1)) {
        errno = scenario == ReadError ? EIO : EINTR;
        return -1;
    }
    if (input_offset == input.size()) return 0;
    const size_t count = std::min(size, scenario == InterruptedReadAndShortWrites ? static_cast<size_t>(3) : size);
    const size_t available = std::min(count, input.size() - input_offset);
    std::memcpy(buffer, input.data() + input_offset, available);
    input_offset += available;
    return static_cast<ssize_t>(available);
}

extern "C" ssize_t host_write(int fd, const void* buffer, size_t size) {
    if (fd != STDOUT_FILENO) return ::write(fd, buffer, size);
    if (scenario == OutputError) {
        errno = EPIPE;
        return -1;
    }
    if (scenario == InterruptedReadAndShortWrites) {
        ++stdout_write_calls;
        if (stdout_write_calls == 1) {
            output.append(static_cast<const char*>(buffer), 2);
            return 2;
        }
        if (stdout_write_calls == 2) {
            errno = EINTR;
            return -1;
        }
    }
    output.append(static_cast<const char*>(buffer), size);
    return static_cast<ssize_t>(size);
}

int __android_log_print(int, const char*, const char*, ...) { return 0; }

int main() {
    bool ok = true;
    ok &= expect(run(ReadError) == 1, "read failure is nonzero");
    ok &= expect(run(OutputError) == 1, "downstream disconnect is nonzero");
    ok &= expect(run(TruncatedHeader) == 1, "truncated frame is nonzero");
    ok &= expect(run(InvalidMagic) == 1, "invalid frame magic is nonzero");
    ok &= expect(run(InterruptedReadAndShortWrites) == 0, "valid SIAO/2 stream tolerates EINTR and short writes");
    ok &= expect(output == "hello", "only DATA payload reaches stdout");
    if (!ok) return 1;
    std::puts("Siano adapter I/O fixtures: PASS");
    return 0;
}
