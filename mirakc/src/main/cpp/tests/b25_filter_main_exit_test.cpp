#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int b25_filter_main_entry(int argc, char** argv);

namespace {

int filter_result = 0;

bool expect(bool condition, const char* label) {
    if (condition) return true;
    std::fprintf(stderr, "B25 main host test failed: %s\n", label);
    return false;
}

int run_direct(int result) {
    filter_result = result;
    const int fd = ::open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    const std::string option = "--reader-fd=" + std::to_string(fd);
    char program[] = "b25-filter";
    char* argv[] = {program, const_cast<char*>(option.c_str()), nullptr};
    return b25_filter_main_entry(2, argv);
}

bool run_socket(int result) {
    filter_result = result;
    const std::string name = "b25-exit-test-" + std::to_string(::getpid()) + "-" + std::to_string(result);
    const int server = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) return false;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, name.data(), name.size());
    const socklen_t length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
    if (::bind(server, reinterpret_cast<sockaddr*>(&address), length) != 0 || ::listen(server, 1) != 0) {
        ::close(server);
        return false;
    }

    std::thread broker([server] {
        const int client = ::accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        if (client >= 0) {
            char request[256];
            (void)::read(client, request, sizeof(request));
            const int reader = ::open("/dev/null", O_RDONLY | O_CLOEXEC);
            char byte = 'R';
            struct iovec iov{&byte, 1};
            char control[CMSG_SPACE(sizeof(int))]{};
            struct msghdr message{};
            message.msg_iov = &iov;
            message.msg_iovlen = 1;
            message.msg_control = control;
            message.msg_controllen = sizeof(control);
            struct cmsghdr* header = CMSG_FIRSTHDR(&message);
            header->cmsg_level = SOL_SOCKET;
            header->cmsg_type = SCM_RIGHTS;
            header->cmsg_len = CMSG_LEN(sizeof(int));
            std::memcpy(CMSG_DATA(header), &reader, sizeof(reader));
            (void)::sendmsg(client, &message, 0);
            if (reader >= 0) ::close(reader);
            ::close(client);
        }
        ::close(server);
    });

    const std::string socket_option = "--socket=@" + name;
    char token_option[] = "--token=fixture";
    char program[] = "b25-filter";
    char* argv[] = {program, const_cast<char*>(socket_option.c_str()), token_option, nullptr};
    const int actual = b25_filter_main_entry(3, argv);
    broker.join();
    return actual == result;
}

}  // namespace

extern "C" int b25_stdio_filter(int) { return filter_result; }

int main() {
    bool ok = true;
    ok &= expect(run_direct(0) == 0, "direct-fd normal EOF exit status");
    ok &= expect(run_direct(1) == 1, "direct-fd I/O failure exit status");
    ok &= expect(run_direct(2) == 2, "direct-fd decoder failure exit status");
    ok &= expect(run_direct(3) == 3, "direct-fd setup failure exit status");
    ok &= expect(run_socket(0), "socket normal EOF exit status");
    ok &= expect(run_socket(1), "socket I/O failure exit status");
    ok &= expect(run_socket(2), "socket decoder failure exit status");
    ok &= expect(run_socket(3), "socket setup failure exit status");
    if (!ok) return 1;
    std::puts("B25 adapter exit host fixtures: PASS");
    return 0;
}
