#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#include <fcntl.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include "arib_std_b25.h"
#include "b_cas_card_error_code.h"
#include "b25_filter_contract.h"

int b25_filter_main_entry(int argc, char** argv);

namespace {

struct SharedState {
    int initial_sigpipe_default;
    int card_released;
    int decoder_released;
    int ccid_closed;
    int get_count;
};

SharedState* shared_state = nullptr;
B_CAS_CARD card{};
ARIB_STD_B25 decoder{};

int card_init(void*) { return 0; }
void card_release(void*) { ++shared_state->card_released; }
void decoder_release(void*) { ++shared_state->decoder_released; }
int decoder_set_int(void*, int) { return 0; }
int decoder_set_card(void*, B_CAS_CARD*) { return 0; }
int decoder_put(void*, ARIB_STD_B25_BUFFER*) { return 0; }
int decoder_flush(void*) { return 0; }
int decoder_get(void*, ARIB_STD_B25_BUFFER* buffer) {
    static std::uint8_t bytes[] = {'f', 'i', 'x', 't', 'u', 'r', 'e'};
    ++shared_state->get_count;
    buffer->data = shared_state->get_count == 1 ? bytes : nullptr;
    buffer->size = shared_state->get_count == 1 ? static_cast<int32_t>(sizeof(bytes)) : 0;
    return 0;
}

void prepare_card_and_decoder() {
    card = {};
    card.private_data = shared_state;
    card.release = card_release;
    card.init = card_init;
    decoder = {};
    decoder.private_data = shared_state;
    decoder.release = decoder_release;
    decoder.set_multi2_round = decoder_set_int;
    decoder.set_strip = decoder_set_int;
    decoder.set_emm_proc = decoder_set_int;
    decoder.set_b_cas_card = decoder_set_card;
    decoder.flush = decoder_flush;
    decoder.put = decoder_put;
    decoder.get = decoder_get;
}

int make_listener(std::string* endpoint) {
    *endpoint = "b25-sigpipe-" + std::to_string(::getpid());
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, endpoint->data(), endpoint->size());
    const socklen_t length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) +
                                                     1 + endpoint->size());
    if (::bind(fd, reinterpret_cast<sockaddr*>(&address), length) != 0 || ::listen(fd, 1) != 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

bool broker_send_reader(int listener) {
    const int client = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
    if (client < 0) return false;
    char request[256];
    const ssize_t request_size = ::read(client, request, sizeof(request));
    const int reader = ::open("/dev/null", O_RDONLY | O_CLOEXEC);
    bool sent = false;
    if (request_size > 0 && reader >= 0) {
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
        sent = ::sendmsg(client, &message, 0) == 1;
    }
    if (reader >= 0) ::close(reader);
    ::close(client);
    return sent;
}

bool expect(bool condition, const char* label) {
    if (condition) return true;
    std::fprintf(stderr, "B25 SIGPIPE subprocess test failed: %s\n", label);
    return false;
}

bool run_case(bool socket_path) {
    void* memory = ::mmap(nullptr, sizeof(SharedState), PROT_READ | PROT_WRITE,
                          MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (memory == MAP_FAILED) return false;
    shared_state = static_cast<SharedState*>(memory);
    std::memset(shared_state, 0, sizeof(*shared_state));
    prepare_card_and_decoder();

    int input_pipe[2];
    int output_pipe[2];
    if (::pipe(input_pipe) != 0 || ::pipe(output_pipe) != 0) return false;
    ::close(output_pipe[0]);

    std::string endpoint;
    const int listener = socket_path ? make_listener(&endpoint) : -1;
    if (socket_path && listener < 0) return false;

    const pid_t child = ::fork();
    if (child < 0) return false;
    if (child == 0) {
        ::close(input_pipe[1]);
        if (socket_path) ::close(listener);
        if (::dup2(input_pipe[0], STDIN_FILENO) < 0 ||
            ::dup2(output_pipe[1], STDOUT_FILENO) < 0) {
            _exit(90);
        }
        if (input_pipe[0] != STDIN_FILENO) ::close(input_pipe[0]);
        if (output_pipe[1] != STDOUT_FILENO) ::close(output_pipe[1]);
        struct sigaction default_sigpipe{};
        default_sigpipe.sa_handler = SIG_DFL;
        sigemptyset(&default_sigpipe.sa_mask);
        if (::sigaction(SIGPIPE, &default_sigpipe, nullptr) != 0) _exit(91);
        struct sigaction current{};
        if (::sigaction(SIGPIPE, nullptr, &current) == 0 && current.sa_handler == SIG_DFL) {
            shared_state->initial_sigpipe_default = 1;
        }

        char program[] = "b25-filter";
        int result;
        if (socket_path) {
            const std::string socket_option = "--socket=@" + endpoint;
            char token[] = "--token=synthetic";
            char* argv[] = {program, const_cast<char*>(socket_option.c_str()), token, nullptr};
            result = b25_filter_main_entry(3, argv);
        } else {
            const int reader = ::open("/dev/null", O_RDONLY | O_CLOEXEC);
            if (reader < 0) _exit(92);
            const std::string reader_option = "--reader-fd=" + std::to_string(reader);
            char* argv[] = {program, const_cast<char*>(reader_option.c_str()), nullptr};
            result = b25_filter_main_entry(2, argv);
        }
        _exit(result);
    }

    ::close(input_pipe[0]);
    ::close(output_pipe[1]);
    const char input[] = "test";
    const bool input_sent = ::write(input_pipe[1], input, sizeof(input) - 1) ==
                            static_cast<ssize_t>(sizeof(input) - 1);
    ::close(input_pipe[1]);
    const bool reader_sent = !socket_path || broker_send_reader(listener);
    if (socket_path) ::close(listener);
    int status = 0;
    const bool reaped = ::waitpid(child, &status, 0) == child;
    const bool exited_with_io_error = reaped && WIFEXITED(status) &&
        WEXITSTATUS(status) == B25_FILTER_IO_ERROR;
    const bool cleaned_up = shared_state->card_released == 1 &&
        shared_state->decoder_released == 1 && shared_state->ccid_closed == 1;
    const bool default_before_main = shared_state->initial_sigpipe_default == 1;
    if (!reader_sent || !default_before_main || !exited_with_io_error || !cleaned_up) {
        if (reaped && WIFSIGNALED(status)) {
            std::fprintf(stderr, "B25 SIGPIPE case socket=%d terminated by signal=%d\n",
                         socket_path ? 1 : 0, WTERMSIG(status));
        } else if (reaped && WIFEXITED(status)) {
            std::fprintf(stderr, "B25 SIGPIPE case socket=%d exit=%d\n",
                         socket_path ? 1 : 0, WEXITSTATUS(status));
        }
        std::fprintf(stderr,
                     "B25 SIGPIPE case socket=%d default=%d card_release=%d decoder_release=%d ccid_close=%d\n",
                     socket_path ? 1 : 0, default_before_main ? 1 : 0,
                     shared_state->card_released, shared_state->decoder_released,
                     shared_state->ccid_closed);
    }
    ::munmap(memory, sizeof(SharedState));
    shared_state = nullptr;
    return input_sent && reader_sent && default_before_main && exited_with_io_error && cleaned_up;
}

}  // namespace

extern "C" int ccid_open(int) { return 0; }
extern "C" void ccid_close(void) { ++shared_state->ccid_closed; }
extern "C" B_CAS_CARD* create_b_cas_card() { return &card; }
extern "C" ARIB_STD_B25* create_arib_std_b25() { return &decoder; }
int __android_log_print(int, const char*, const char*, ...) { return 0; }

int main() {
    const bool direct = run_case(false);
    const bool socket = run_case(true);
    bool ok = expect(direct, "direct-FD broken pipe returns I/O status and releases resources");
    ok &= expect(socket, "socket broken pipe returns I/O status and releases resources");
    if (!ok) return 1;
    std::puts("B25 real-pipe SIGPIPE subprocess fixtures: PASS");
    return 0;
}
