#include <cerrno>
#include <climits>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

extern "C" int b25_stdio_filter(int reader_fd);

namespace {

int parse_reader_fd(const char* value) {
    if (value == nullptr || *value == '\0') return -1;
    errno = 0;
    char* end = nullptr;
    const long parsed = std::strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || parsed < 0 || parsed > INT_MAX) return -1;
    return static_cast<int>(parsed);
}

int connect_abstract(const char* endpoint) {
    if (endpoint == nullptr || endpoint[0] != '@') {
        errno = EINVAL;
        return -1;
    }
    const char* name = endpoint + 1;
    const size_t name_length = std::strlen(name);
    if (name_length == 0 || name_length + 1 >= sizeof(((sockaddr_un*)nullptr)->sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, name, name_length);
    const socklen_t length =
        static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name_length);
    if (::connect(fd, reinterpret_cast<sockaddr*>(&address), length) != 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

int request_reader_fd(const char* endpoint, const char* token) {
    if (endpoint == nullptr || token == nullptr) return -1;
    const int socket_fd = connect_abstract(endpoint);
    if (socket_fd < 0) return -1;
    char request[256];
    const int request_length = std::snprintf(request, sizeof(request), "READER %s\n", token);
    if (request_length <= 0 || request_length >= static_cast<int>(sizeof(request)) ||
        ::write(socket_fd, request, static_cast<size_t>(request_length)) != request_length) {
        ::close(socket_fd);
        return -1;
    }

    char buffer[16];
    struct iovec iov = {buffer, sizeof(buffer)};
    char control[CMSG_SPACE(sizeof(int))];
    std::memset(control, 0, sizeof(control));
    struct msghdr message{};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);

    const ssize_t received = ::recvmsg(socket_fd, &message, 0);
    if (received <= 0) {
        ::close(socket_fd);
        return -1;
    }
    int reader_fd = -1;
    for (struct cmsghdr* header = CMSG_FIRSTHDR(&message); header != nullptr;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS &&
            header->cmsg_len >= CMSG_LEN(sizeof(int))) {
            std::memcpy(&reader_fd, CMSG_DATA(header), sizeof(reader_fd));
            break;
        }
    }
    if (reader_fd < 0) {
        ::close(socket_fd);
        return -1;
    }
    // Keep the socket open until the filter finishes so the broker can
    // observe EOF and release its reader lease for the next filter.
    const int lease_socket = socket_fd;
    const int result = b25_stdio_filter(reader_fd);
    ::close(reader_fd);
    ::close(lease_socket);
    return result;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::strncmp(argv[1], "--reader-fd=", 12) == 0) {
        const int reader_fd = parse_reader_fd(argv[1] + 12);
        if (reader_fd < 0) return 64;
        const int result = b25_stdio_filter(reader_fd);
        ::close(reader_fd);
        return result;
    }

    if (argc == 3) {
        const char* endpoint = nullptr;
        const char* token = nullptr;
        for (int index = 1; index < argc; ++index) {
            if (std::strncmp(argv[index], "--socket=", 9) == 0) {
                endpoint = argv[index] + 9;
            } else if (std::strncmp(argv[index], "--token=", 8) == 0) {
                token = argv[index] + 8;
            }
        }
        if (endpoint == nullptr || token == nullptr) return 64;
        return request_reader_fd(endpoint, token) < 0 ? 65 : 0;
    }

    return 64;
}
