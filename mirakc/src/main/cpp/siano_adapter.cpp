#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstddef>
#include <cstring>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <android/log.h>
#include "siano_stream_protocol.h"

namespace {

constexpr char kProtocol[] = "SIAO/2";
constexpr char kLogTag[] = "SianoAdapter";

void log_info(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message);
}

void log_error_errno(const char* stage, int error) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s errno=%d", stage, error);
}

bool value_after(const char* argument, const char* name, std::string* value) {
    const std::size_t length = std::strlen(name);
    if (std::strncmp(argument, name, length) != 0 || argument[length] == '\0') return false;
    *value = argument + length;
    return true;
}

bool write_all(int fd, const char* data, std::size_t size) {
    while (size > 0) {
        const ssize_t count = ::write(fd, data, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        data += count;
        size -= static_cast<std::size_t>(count);
    }
    return true;
}

std::uint32_t read_u32_be(const unsigned char* bytes) {
    return (static_cast<std::uint32_t>(bytes[0]) << 24U) |
        (static_cast<std::uint32_t>(bytes[1]) << 16U) |
        (static_cast<std::uint32_t>(bytes[2]) << 8U) |
        static_cast<std::uint32_t>(bytes[3]);
}

int read_exact(int fd, unsigned char* data, std::size_t size) {
    std::size_t read_bytes = 0;
    while (read_bytes < size) {
        const ssize_t count = ::read(fd, data + read_bytes, size - read_bytes);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -1;
        if (count == 0) return read_bytes == 0 ? 0 : -1;
        read_bytes += static_cast<std::size_t>(count);
    }
    return 1;
}

bool wait_for_stream_eof(int fd) {
    constexpr auto kTimeout = std::chrono::milliseconds(250);
    const auto deadline = std::chrono::steady_clock::now() + kTimeout;
    while (true) {
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now());
        if (remaining.count() <= 0) return false;
        const int timeout = static_cast<int>(remaining.count() + 1);
        struct pollfd event{fd, POLLIN | POLLHUP | POLLERR, 0};
        const int ready = ::poll(&event, 1, timeout);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || (event.revents & POLLNVAL) != 0) return false;
        if ((event.revents & (POLLIN | POLLHUP)) == 0) return false;
        unsigned char trailing = 0;
        const ssize_t count = ::read(fd, &trailing, 1);
        if (count == 0) return true;
        if (count > 0) return false;
        if (errno != EINTR) return false;
    }
}

int stream_exit_code(std::uint32_t kind, std::uint32_t detail) {
    using mirakc::siano_stream::Outcome;
    if (kind == static_cast<std::uint32_t>(Outcome::exit)) {
        return detail <= 255U ? static_cast<int>(detail) : -1;
    }
    if (kind == static_cast<std::uint32_t>(Outcome::signal)) {
        return detail > 0U && detail <= 255U
            ? static_cast<int>(detail + 128U > 255U ? 255U : detail + 128U) : -1;
    }
    if (kind >= static_cast<std::uint32_t>(Outcome::timeout) &&
        kind <= static_cast<std::uint32_t>(Outcome::broker_io) && detail == 0U) {
        return 1;
    }
    return -1;
}

int connect_abstract(const std::string& endpoint) {
    if (endpoint.empty() || endpoint[0] != '@') {
        errno = EINVAL;
        return -1;
    }
    const std::string name = endpoint.substr(1);
    if (name.empty() || name.size() + 1 >= sizeof(((sockaddr_un*)nullptr)->sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, name.data(), name.size());
    const socklen_t length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
    if (::connect(fd, reinterpret_cast<sockaddr*>(&address), length) != 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

}  // namespace

int siano_adapter_stream_main(int fd) {
    unsigned char header[12];
    unsigned char payload[mirakc::siano_stream::kMaxDataBytes];
    while (true) {
        const int header_result = read_exact(fd, header, sizeof(header));
        if (header_result != 1) {
            log_info(header_result == 0 ? "stream ended without END" : "invalid stream header");
            return 1;
        }
        if (std::memcmp(header, mirakc::siano_stream::kMagic, sizeof(mirakc::siano_stream::kMagic)) != 0) {
            log_info("invalid stream magic");
            return 1;
        }
        const std::uint32_t type = read_u32_be(header + 4);
        const std::uint32_t length = read_u32_be(header + 8);
        if (type == mirakc::siano_stream::kData) {
            if (length == 0U || length > mirakc::siano_stream::kMaxDataBytes) {
                log_info("invalid DATA length");
                return 1;
            }
            if (read_exact(fd, payload, length) != 1) {
                log_info("truncated DATA payload");
                return 1;
            }
            if (!write_all(STDOUT_FILENO, reinterpret_cast<const char*>(payload), length)) {
                log_error_errno("stdout write failure", errno);
                return 1;
            }
            continue;
        }
        if (type != mirakc::siano_stream::kEnd || length != mirakc::siano_stream::kEndPayloadBytes) {
            log_info("invalid stream frame type or length");
            return 1;
        }
        unsigned char end[mirakc::siano_stream::kEndPayloadBytes];
        if (read_exact(fd, end, sizeof(end)) != 1) {
            log_info("truncated END payload");
            return 1;
        }
        const int result = stream_exit_code(read_u32_be(end), read_u32_be(end + 4));
        if (result < 0) {
            log_info("invalid END outcome");
            return 1;
        }
        if (!wait_for_stream_eof(fd)) {
            log_info("stream did not close cleanly after END");
            return 1;
        }
        return result;
    }
}

int main(int argc, char** argv) {
    std::signal(SIGPIPE, SIG_IGN);
    std::string socket;
    std::string token;
    std::string index;
    std::string channel;
    for (int i = 1; i < argc; ++i) {
        if (value_after(argv[i], "--socket=", &socket) ||
            value_after(argv[i], "--token=", &token) ||
            value_after(argv[i], "--tuner-index=", &index) ||
            value_after(argv[i], "--channel=", &channel)) continue;
        log_info("parse failure");
        return 64;
    }
    if (socket.empty() || token.empty() || index.empty() || channel.empty()) {
        log_info("parse failure");
        return 64;
    }
    const int fd = connect_abstract(socket);
    if (fd < 0) {
        log_error_errno("connect failure", errno);
        return 1;
    }
    log_info("connected");
    const std::string request = std::string(kProtocol) + " " + token + " " + index + " " + channel + "\n";
    if (!write_all(fd, request.data(), request.size())) {
        log_error_errno("request write failure", errno);
        ::close(fd);
        return 1;
    }
    log_info("request sent");

    const int result = siano_adapter_stream_main(fd);
    ::close(fd);
    return result;
}
