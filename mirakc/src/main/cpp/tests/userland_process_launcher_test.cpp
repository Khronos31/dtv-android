#include "userland_process_launcher.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <string>
#include <thread>
#include <vector>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <sys/wait.h>

namespace {

bool read_exact(int fd, char* buffer, std::size_t length) {
    std::size_t offset = 0;
    while (offset < length) {
        const ssize_t count = read(fd, buffer + offset, length - offset);
        if (count > 0) offset += static_cast<std::size_t>(count);
        else if (count < 0 && errno == EINTR) continue;
        else return false;
    }
    return true;
}

int child_main() {
    char primary[5];
    char sibling[4];
    if (!read_exact(3, primary, sizeof(primary)) || !read_exact(4, sibling, sizeof(sibling))) return 11;
    if (std::memcmp(primary, "alpha", sizeof(primary)) != 0 ||
        std::memcmp(sibling, "beta", sizeof(sibling)) != 0) return 12;
    errno = 0;
    if (fcntl(5, F_GETFD) != -1 || errno != EBADF) return 14;
    const char result[] = "USB=alpha,beta\n";
    if (write(STDOUT_FILENO, result, sizeof(result) - 1U) != static_cast<ssize_t>(sizeof(result) - 1U)) return 13;
    usleep(800000);
    return 0;
}

int ts_child_main() {
    char input = 0;
    if (read(STDIN_FILENO, &input, 1) != 0) return 31;
    const char ts[] = "TS=payload\n";
    const char diagnostic[] = "DIAG=separate\n";
    if (write(STDOUT_FILENO, ts, sizeof(ts) - 1U) != static_cast<ssize_t>(sizeof(ts) - 1U)) return 32;
    if (write(STDERR_FILENO, diagnostic, sizeof(diagnostic) - 1U) !=
        static_cast<ssize_t>(sizeof(diagnostic) - 1U)) return 33;
    return 0;
}

bool test_concurrent_launch_reap(const char* executable) {
    constexpr int kCallers = 8;
    constexpr int kLaunchesPerCaller = 16;
    std::atomic<bool> success{true};
    std::vector<std::thread> callers;
    callers.reserve(kCallers);
    for (int caller = 0; caller < kCallers; ++caller) {
        callers.emplace_back([&] {
            for (int launch = 0; launch < kLaunchesPerCaller; ++launch) {
                const char* child_arguments[]{executable, "--quick-child", nullptr};
                const auto started = mirakc::userland_process::start_command(
                    executable, child_arguments, -1, -1);
                if (started.pid <= 0 || started.output_fd < 0) {
                    if (started.output_fd >= 0) close(started.output_fd);
                    success.store(false);
                    continue;
                }
                close(started.output_fd);
                int status = 0;
                pid_t waited = -1;
                do {
                    waited = waitpid(started.pid, &status, 0);
                } while (waited < 0 && errno == EINTR);
                if (waited != started.pid || !WIFEXITED(status) ||
                    WEXITSTATUS(status) != 0) success.store(false);
            }
        });
    }
    for (auto& caller : callers) caller.join();
    return success.load();
}

bool wait_for_output(int fd, std::string& output) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    char buffer[64];
    while (std::chrono::steady_clock::now() < deadline) {
        pollfd descriptor{fd, POLLIN | POLLHUP, 0};
        const int ready = poll(&descriptor, 1, 100);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return false;
        if (ready == 0) continue;
        const ssize_t count = read(fd, buffer, sizeof(buffer));
        if (count > 0) output.append(buffer, static_cast<std::size_t>(count));
        else if (count == 0) return true;
        else if (errno != EINTR) return false;
    }
    return false;
}

bool test_parent_process_death() {
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) return false;
    char lock_path[] = "/tmp/userland-launcher-lock-XXXXXX";
    const int temporary = mkstemp(lock_path);
    if (temporary < 0) return false;
    close(temporary);
    int report_pipe[2];
    int control_pipe[2];
    if (pipe(report_pipe) != 0 || pipe(control_pipe) != 0) {
        unlink(lock_path);
        return false;
    }

    const pid_t fixture = fork();
    if (fixture < 0) {
        unlink(lock_path);
        return false;
    }
    if (fixture == 0) {
        close(report_pipe[0]);
        close(control_pipe[1]);
        const int lock_fd = open(lock_path, O_RDWR);
        if (lock_fd < 0 || flock(lock_fd, LOCK_EX | LOCK_NB) != 0) _exit(20);
        mirakc::userland_process::LaunchResult started;
        std::thread caller([&] {
            const char* child_arguments[] = {"sleep", "30", nullptr};
            started = mirakc::userland_process::start_command(
                "/bin/sleep", child_arguments, lock_fd, -1);
        });
        caller.join();
        if (started.pid <= 0 || started.output_fd < 0) _exit(21);
        char pid_text[32];
        const int length = std::snprintf(pid_text, sizeof(pid_text), "%d\n", started.pid);
        if (write(report_pipe[1], pid_text, static_cast<std::size_t>(length)) != length) _exit(22);
        close(started.output_fd);
        char release = 0;
        if (read(control_pipe[0], &release, 1) != 1 || release != 'x') _exit(23);
        close(lock_fd);
        _exit(0);
    }

    close(report_pipe[1]);
    close(control_pipe[0]);
    char pid_text[32]{};
    const ssize_t count = read(report_pipe[0], pid_text, sizeof(pid_text) - 1U);
    close(report_pipe[0]);
    const pid_t daemon = count > 0 ? static_cast<pid_t>(std::strtol(pid_text, nullptr, 10)) : -1;
    const int lock_fd = open(lock_path, O_RDWR);
    bool alive_after_caller_exit = daemon > 0 && kill(daemon, 0) == 0;
    bool lock_held = lock_fd >= 0 && flock(lock_fd, LOCK_EX | LOCK_NB) != 0 &&
        (errno == EWOULDBLOCK || errno == EAGAIN);
    usleep(100000);
    alive_after_caller_exit = alive_after_caller_exit && kill(daemon, 0) == 0;
    const char release = 'x';
    const bool released_parent = write(control_pipe[1], &release, 1) == 1;
    close(control_pipe[1]);
    int fixture_status = 0;
    const bool fixture_ok = waitpid(fixture, &fixture_status, 0) == fixture &&
        WIFEXITED(fixture_status) && WEXITSTATUS(fixture_status) == 0;

    bool fd_released_on_parent_death = false;
    bool daemon_died_with_app = false;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < deadline) {
        if (!fd_released_on_parent_death && lock_fd >= 0 && flock(lock_fd, LOCK_EX | LOCK_NB) == 0) {
            fd_released_on_parent_death = true;
        }
        if (!daemon_died_with_app && daemon > 0) {
            int daemon_status = 0;
            const pid_t waited = waitpid(daemon, &daemon_status, WNOHANG);
            daemon_died_with_app = waited == daemon && WIFSIGNALED(daemon_status) &&
                WTERMSIG(daemon_status) == SIGKILL;
        }
        if (fd_released_on_parent_death && daemon_died_with_app) break;
        usleep(20000);
    }
    if (lock_fd >= 0) close(lock_fd);
    if (!fd_released_on_parent_death && daemon > 0) kill(-daemon, SIGKILL);
    unlink(lock_path);
    if (!alive_after_caller_exit || !lock_held || !released_parent || !fixture_ok ||
        !daemon_died_with_app || !fd_released_on_parent_death) {
        std::fprintf(stderr, "parent-death lifetime failed: daemon=%d alive=%d lock-held=%d fixture=%d child-died=%d fd-released=%d\n",
                     daemon, alive_after_caller_exit, lock_held, fixture_ok, daemon_died_with_app,
                     fd_released_on_parent_death);
        return false;
    }
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::strcmp(argv[1], "--child") == 0) return child_main();
    if (argc == 2 && std::strcmp(argv[1], "--ts-child") == 0) return ts_child_main();
    if (argc == 2 && std::strcmp(argv[1], "--quick-child") == 0) return 0;
    if (argc != 1) return 2;
    if (!test_parent_process_death()) return 1;
    if (!test_concurrent_launch_reap(argv[0])) {
        std::fprintf(stderr, "concurrent launch/reap stress failed\n");
        return 1;
    }

    rlimit limit{};
    if (getrlimit(RLIMIT_NOFILE, &limit) != 0) return 1;
    const rlim_t close_limit = limit.rlim_cur == RLIM_INFINITY ? 65536 : std::min<rlim_t>(limit.rlim_cur, 65536);
    for (int fd = STDERR_FILENO + 1; fd < static_cast<int>(close_limit); ++fd) close(fd);

    int primary_pipe[2];
    int sibling_pipe[2];
    if (pipe(primary_pipe) != 0 || pipe(sibling_pipe) != 0) return 1;
    if (write(primary_pipe[1], "alpha", 5) != 5 || write(sibling_pipe[1], "beta", 4) != 4) return 1;
    const int primary_source = fcntl(primary_pipe[0], F_DUPFD_CLOEXEC, 20);
    const int sibling_source = fcntl(sibling_pipe[0], F_DUPFD_CLOEXEC, 20);
    if (primary_source < 0 || sibling_source < 0) return 1;
    close(primary_pipe[0]); close(primary_pipe[1]);
    close(sibling_pipe[0]); close(sibling_pipe[1]);

    mirakc::userland_process::LaunchResult started;
    std::thread launcher([&] {
        char* child_arguments[] = {argv[0], const_cast<char*>("--child"), nullptr};
        started = mirakc::userland_process::start_command(
            argv[0], const_cast<const char* const*>(child_arguments), primary_source, sibling_source);
    });
    launcher.join();
    close(primary_source);
    close(sibling_source);
    if (started.pid <= 0 || started.output_fd < 0) {
        std::fprintf(stderr, "shared process launcher failed to start child\n");
        return 1;
    }

    int status = 0;
    if (waitpid(started.pid, &status, WNOHANG) != 0) {
        std::fprintf(stderr, "child did not survive its creator thread\n");
        close(started.output_fd);
        return 1;
    }
    std::string output;
    const bool output_ok = wait_for_output(started.output_fd, output);
    close(started.output_fd);
    const pid_t waited = waitpid(started.pid, &status, 0);
    if (!output_ok || output != "USB=alpha,beta\n" || waited != started.pid ||
        !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        std::fprintf(stderr, "FD mapping or launch-thread lifetime failed: output=%s status=%d\n",
                     output.c_str(), status);
        return 1;
    }

    const char* ts_arguments[] = {argv[0], "--ts-child", nullptr};
    const auto ts_started = mirakc::userland_process::start_ts_client(argv[0], ts_arguments);
    if (ts_started.pid <= 0 || ts_started.ts_fd < 0 || ts_started.diagnostic_fd < 0) return 1;
    std::string ts_output;
    std::string diagnostics;
    const bool ts_output_ok = wait_for_output(ts_started.ts_fd, ts_output);
    const bool diagnostics_ok = wait_for_output(ts_started.diagnostic_fd, diagnostics);
    close(ts_started.ts_fd);
    close(ts_started.diagnostic_fd);
    const pid_t ts_waited = waitpid(ts_started.pid, &status, 0);
    if (!ts_output_ok || !diagnostics_ok || ts_output != "TS=payload\n" ||
        diagnostics != "DIAG=separate\n" || ts_waited != ts_started.pid ||
        !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        std::fprintf(stderr, "TS output/diagnostic separation failed: ts=%s diagnostics=%s\n",
                     ts_output.c_str(), diagnostics.c_str());
        return 1;
    }
    std::puts("shared process launch: low-FD remap, caller-thread survival, parent-death, FD release, TS stream separation, concurrent launch/reap (8x16) PASS");
    return 0;
}
