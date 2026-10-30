#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int siano_adapter_main_entry(int argc, char** argv);

namespace {

enum Scenario {
    PollError,
    InvalidPollFlag,
    ReadError,
    OutputError,
    PollErrorAtEof,
    InterruptedPollAndShortWrites
};
Scenario scenario = PollError;
int poll_calls = 0;
int read_calls = 0;
int stdout_write_calls = 0;
std::string output;

bool expect(bool condition, const char* label) {
    if (condition) return true;
    std::fprintf(stderr, "Siano host test failed: %s\n", label);
    return false;
}

int run(Scenario next) {
    scenario = next;
    poll_calls = 0;
    read_calls = 0;
    stdout_write_calls = 0;
    output.clear();
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

extern "C" int host_poll(struct pollfd* events, nfds_t count, int) {
    if (count != 1) return -1;
    ++poll_calls;
    if (scenario == PollError) {
        errno = EIO;
        return -1;
    }
    if (scenario == InterruptedPollAndShortWrites && poll_calls == 1) {
        errno = EINTR;
        return -1;
    }
    if (scenario == InvalidPollFlag) events[0].revents = POLLNVAL;
    else if (scenario == PollErrorAtEof) events[0].revents = POLLHUP | POLLERR;
    else if (scenario == ReadError || scenario == OutputError) events[0].revents = POLLIN;
    else events[0].revents = POLLHUP;
    return 1;
}

extern "C" ssize_t host_read(int, void* buffer, size_t size) {
    ++read_calls;
    if (scenario == ReadError) {
        errno = EIO;
        return -1;
    }
    if (scenario == OutputError) {
        const char data[] = "ts";
        std::memcpy(buffer, data, sizeof(data) - 1);
        return sizeof(data) - 1;
    }
    if (scenario == InterruptedPollAndShortWrites && read_calls == 1) {
        const char data[] = "hello";
        const size_t count = size < sizeof(data) - 1 ? size : sizeof(data) - 1;
        std::memcpy(buffer, data, count);
        return static_cast<ssize_t>(count);
    }
    return 0;
}

extern "C" ssize_t host_write(int fd, const void* buffer, size_t size) {
    if (fd != STDOUT_FILENO) return ::write(fd, buffer, size);
    if (scenario == OutputError) {
        errno = EPIPE;
        return -1;
    }
    if (scenario == InterruptedPollAndShortWrites) {
        ++stdout_write_calls;
        if (stdout_write_calls == 1) {
            output.append(static_cast<const char*>(buffer), 2);
            return 2;
        }
        if (stdout_write_calls == 2) {
            errno = EINTR;
            return -1;
        }
        output.append(static_cast<const char*>(buffer), size);
        return static_cast<ssize_t>(size);
    }
    return static_cast<ssize_t>(size);
}

int __android_log_print(int, const char*, const char*, ...) { return 0; }

int main() {
    bool ok = true;
    ok &= expect(run(PollError) == 1, "poll failure is nonzero");
    ok &= expect(run(InvalidPollFlag) == 1, "POLLNVAL is nonzero");
    ok &= expect(run(ReadError) == 1, "read failure is nonzero");
    ok &= expect(run(OutputError) == 1, "downstream disconnect is nonzero");
    ok &= expect(run(PollErrorAtEof) == 1, "POLLERR with EOF is nonzero");
    ok &= expect(run(InterruptedPollAndShortWrites) == 0, "EINTR and clean EOF succeed");
    ok &= expect(output == "hello", "short writes preserve all output bytes");
    if (!ok) return 1;
    std::puts("Siano adapter I/O host fixtures: PASS");
    return 0;
}
