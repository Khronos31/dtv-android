#include <cerrno>
#include <csignal>
#include <cstddef>
#include <cstring>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <android/log.h>

namespace {

constexpr char kProtocol[] = "SIAO/1";
constexpr char kLogTag[] = "SianoAdapter";

void log_info(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message);
}

void log_error_errno(const char* stage, int error) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s errno=%d", stage, error);
}

void log_error_revents(short revents) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "socket event revents=%d", static_cast<int>(revents));
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

    char buffer[32 * 1024];
    while (true) {
        struct pollfd event{fd, POLLIN | POLLHUP | POLLERR, 0};
        const int ready = ::poll(&event, 1, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            log_error_errno("poll failure", errno);
            break;
        }
        if ((event.revents & POLLIN) != 0) {
            const ssize_t count = ::read(fd, buffer, sizeof(buffer));
            if (count == 0) {
                log_info("socket EOF");
                break;
            }
            if (count < 0) {
                if (errno == EINTR) continue;
                log_error_errno("read failure", errno);
                break;
            }
            if (!write_all(STDOUT_FILENO, buffer, static_cast<std::size_t>(count))) {
                log_error_errno("stdout write failure", errno);
                break;
            }
            // POLLIN|POLLHUP can carry one or more final TS chunks. Return
            // to poll once more so queued bytes are drained before HUP ends.
            continue;
        } else if ((event.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            log_error_revents(event.revents);
            break;
        }
    }
    ::close(fd);
    return 0;
}
