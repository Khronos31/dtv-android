#ifndef MIRAKC_USERLAND_PROCESS_LAUNCHER_H_
#define MIRAKC_USERLAND_PROCESS_LAUNCHER_H_

#include <cerrno>
#include <condition_variable>
#include <csignal>
#include <deque>
#include <fcntl.h>
#include <mutex>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

namespace mirakc::userland_process {

struct LaunchResult {
    int output_fd = -1;
    pid_t pid = -1;
};

struct TsClientLaunchResult {
    int ts_fd = -1;
    int diagnostic_fd = -1;
    pid_t pid = -1;
};

inline int duplicate_for_exec(int source_fd) {
    if (source_fd < 0) return -1;
    return fcntl(source_fd, F_DUPFD_CLOEXEC, 10);
}

inline void close_inherited_descriptors(long limit, int first_fd, int second_fd) {
    if (limit < 0 || limit > 65536) limit = 65536;
    for (int fd = STDERR_FILENO + 1; fd < limit; ++fd) {
        if (fd != first_fd && fd != second_fd) close(fd);
    }
}

namespace detail {

enum class LaunchKind { COMMAND, TS_CLIENT };

struct Request {
    LaunchKind kind = LaunchKind::COMMAND;
    const char* executable = nullptr;
    const char* const* argv = nullptr;
    const char* working_directory = nullptr;
    long descriptor_limit = 0;
    pid_t parent_pid = -1;
    int first_usb_fd = -1;
    int second_usb_fd = -1;
    int input_fd = -1;
    int output_fd = -1;
    int diagnostic_fd = -1;
    LaunchResult command_result;
    TsClientLaunchResult ts_result;
    std::mutex completion_mutex;
    std::condition_variable completion;
    bool completed = false;
};

[[noreturn]] inline void run_child(const Request& request) {
    if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) != 0 || getppid() != request.parent_pid) _exit(127);
    setpgid(0, 0);
    if (request.kind == LaunchKind::COMMAND) {
        if ((request.first_usb_fd >= 0 && dup2(request.first_usb_fd, 3) < 0) ||
            (request.second_usb_fd >= 0 && dup2(request.second_usb_fd, 4) < 0)) {
            const char message[] = "userland: USB descriptor setup failed\n";
            write(request.diagnostic_fd, message, sizeof(message) - 1U);
            _exit(127);
        }
    }
    if ((request.input_fd >= 0 && dup2(request.input_fd, STDIN_FILENO) < 0) ||
        (request.output_fd >= 0 && dup2(request.output_fd, STDOUT_FILENO) < 0) ||
        (request.diagnostic_fd >= 0 && dup2(request.diagnostic_fd, STDERR_FILENO) < 0)) {
        const char message[] = "userland: standard descriptor setup failed\n";
        if (request.diagnostic_fd >= 0) write(request.diagnostic_fd, message, sizeof(message) - 1U);
        _exit(127);
    }
    if (request.input_fd < 0) {
        const int null_fd = open("/dev/null", O_RDONLY);
        if (null_fd < 0 || dup2(null_fd, STDIN_FILENO) < 0) _exit(127);
        if (null_fd > STDERR_FILENO) close(null_fd);
    }
    close_inherited_descriptors(request.descriptor_limit,
                                request.first_usb_fd >= 0 ? 3 : -1,
                                request.second_usb_fd >= 0 ? 4 : -1);
    if (request.working_directory != nullptr && chdir(request.working_directory) != 0) {
        const char message[] = "userland: working directory setup failed\n";
        write(STDERR_FILENO, message, sizeof(message) - 1U);
        _exit(127);
    }
    execv(request.executable, const_cast<char* const*>(request.argv));
    const char message[] = "userland: exec failed\n";
    write(STDERR_FILENO, message, sizeof(message) - 1U);
    _exit(127);
}

inline void run_fork(Request& request) {
    const pid_t child = fork();
    if (child < 0) return;
    if (child == 0) run_child(request);
    setpgid(child, child);
    if (request.kind == LaunchKind::COMMAND) request.command_result.pid = child;
    else request.ts_result.pid = child;
}

class Launcher {
public:
    Launcher() { std::thread([this] { run(); }).detach(); }

    LaunchResult launch_command(Request& request) {
        submit(request);
        return request.command_result;
    }

    TsClientLaunchResult launch_ts_client(Request& request) {
        submit(request);
        return request.ts_result;
    }

private:
    void submit(Request& request) {
        {
            std::lock_guard<std::mutex> guard(queue_mutex_);
            requests_.push_back(&request);
        }
        queue_changed_.notify_one();
        std::unique_lock<std::mutex> guard(request.completion_mutex);
        request.completion.wait(guard, [&request] { return request.completed; });
    }

    void run() {
        for (;;) {
            Request* request = nullptr;
            {
                std::unique_lock<std::mutex> guard(queue_mutex_);
                queue_changed_.wait(guard, [this] { return !requests_.empty(); });
                request = requests_.front();
                requests_.pop_front();
            }
            run_fork(*request);
            {
                std::lock_guard<std::mutex> guard(request->completion_mutex);
                request->completed = true;
                request->completion.notify_one();
            }
        }
    }

    std::mutex queue_mutex_;
    std::condition_variable queue_changed_;
    std::deque<Request*> requests_;
};

inline Launcher& launcher() {
    static Launcher* value = new Launcher();
    return *value;
}

inline bool submit(Request& request) {
    try {
        if (request.kind == LaunchKind::COMMAND) {
            (void)launcher().launch_command(request);
        } else {
            (void)launcher().launch_ts_client(request);
        }
        return true;
    } catch (...) {
        return false;
    }
}

}  // namespace detail

inline LaunchResult start_command(const char* executable, const char* const argv[],
                                  int first_usb_fd, int second_usb_fd,
                                  const char* working_directory = nullptr) {
    if (executable == nullptr || executable[0] == '\0' || argv == nullptr ||
        (second_usb_fd >= 0 && first_usb_fd < 0) ||
        (first_usb_fd >= 0 && first_usb_fd == second_usb_fd)) return {};

    long descriptor_limit = sysconf(_SC_OPEN_MAX);
    if (descriptor_limit < 0 || descriptor_limit > 65536) descriptor_limit = 65536;
    int log_pipe[2];
    if (pipe2(log_pipe, O_CLOEXEC) != 0) return {};
    const int first = duplicate_for_exec(first_usb_fd);
    const int second = duplicate_for_exec(second_usb_fd);
    const int log = duplicate_for_exec(log_pipe[1]);
    if ((first_usb_fd >= 0 && first < 0) || (second_usb_fd >= 0 && second < 0) || log < 0) {
        if (first >= 0) close(first);
        if (second >= 0) close(second);
        if (log >= 0) close(log);
        close(log_pipe[0]);
        close(log_pipe[1]);
        return {};
    }

    detail::Request request;
    request.kind = detail::LaunchKind::COMMAND;
    request.executable = executable;
    request.argv = argv;
    request.working_directory = working_directory;
    request.descriptor_limit = descriptor_limit;
    request.parent_pid = getpid();
    request.first_usb_fd = first;
    request.second_usb_fd = second;
    request.output_fd = log;
    request.diagnostic_fd = log;
    const bool submitted = detail::submit(request);
    if (first >= 0) close(first);
    if (second >= 0) close(second);
    close(log);
    close(log_pipe[1]);
    if (!submitted || request.command_result.pid <= 0) {
        close(log_pipe[0]);
        return {};
    }
    request.command_result.output_fd = log_pipe[0];
    return request.command_result;
}

inline TsClientLaunchResult start_ts_client(const char* executable, const char* const argv[]) {
    if (executable == nullptr || executable[0] == '\0' || argv == nullptr) return {};
    long descriptor_limit = sysconf(_SC_OPEN_MAX);
    if (descriptor_limit < 0 || descriptor_limit > 65536) descriptor_limit = 65536;
    int ts_pipe[2];
    int diagnostic_pipe[2];
    if (pipe2(ts_pipe, O_CLOEXEC) != 0) return {};
    if (pipe2(diagnostic_pipe, O_CLOEXEC) != 0) {
        close(ts_pipe[0]); close(ts_pipe[1]);
        return {};
    }
    const int ts = duplicate_for_exec(ts_pipe[1]);
    const int diagnostic = duplicate_for_exec(diagnostic_pipe[1]);
    const int null_input = open("/dev/null", O_RDONLY | O_CLOEXEC);
    const int input = null_input < 0 ? -1 : fcntl(null_input, F_DUPFD_CLOEXEC, 10);
    if (ts < 0 || diagnostic < 0 || input < 0) {
        if (ts >= 0) close(ts);
        if (diagnostic >= 0) close(diagnostic);
        if (null_input >= 0) close(null_input);
        if (input >= 0) close(input);
        close(ts_pipe[0]); close(ts_pipe[1]);
        close(diagnostic_pipe[0]); close(diagnostic_pipe[1]);
        return {};
    }
    close(null_input);

    detail::Request request;
    request.kind = detail::LaunchKind::TS_CLIENT;
    request.executable = executable;
    request.argv = argv;
    request.descriptor_limit = descriptor_limit;
    request.parent_pid = getpid();
    request.input_fd = input;
    request.output_fd = ts;
    request.diagnostic_fd = diagnostic;
    const bool submitted = detail::submit(request);
    close(input); close(ts); close(diagnostic);
    close(ts_pipe[1]); close(diagnostic_pipe[1]);
    if (!submitted || request.ts_result.pid <= 0) {
        close(ts_pipe[0]); close(diagnostic_pipe[0]);
        return {};
    }
    request.ts_result.ts_fd = ts_pipe[0];
    request.ts_result.diagnostic_fd = diagnostic_pipe[0];
    return request.ts_result;
}

}  // namespace mirakc::userland_process

#endif  // MIRAKC_USERLAND_PROCESS_LAUNCHER_H_
