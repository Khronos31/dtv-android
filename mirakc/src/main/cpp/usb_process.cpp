#include <jni.h>

#include <cerrno>
#include <csignal>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstring>
#include <string>

namespace {

std::string stringFromJni(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

long descriptor_limit() {
    long limit = sysconf(_SC_OPEN_MAX);
    if (limit < 0 || limit > 65536) limit = 65536;
    return limit;
}

void close_inherited_descriptors_bounded(
    long limit, int preserved_fd = -1, int second_preserved_fd = -1) {
    if (limit < 0 || limit > 65536) limit = 65536;
    for (int fd = STDERR_FILENO + 1; fd < limit; ++fd) {
        if (fd != preserved_fd && fd != second_preserved_fd) close(fd);
    }
}

void close_inherited_descriptors(int preserved_fd = -1, int second_preserved_fd = -1) {
    close_inherited_descriptors_bounded(descriptor_limit(), preserved_fd, second_preserved_fd);
}

int duplicate_for_exec(int source_fd) {
    if (source_fd < 0) return -1;
    return fcntl(source_fd, F_DUPFD_CLOEXEC, 10);
}

void reap_process_group(pid_t pgid) {
    int status = 0;
    for (int attempt = 0; attempt < 200; ++attempt) {
        const pid_t child = waitpid(-pgid, &status, WNOHANG);
        if (child > 0) continue;
        if (child < 0 && (errno == ECHILD || errno == ESRCH)) return;
        usleep(10 * 1000);
    }
}

int poll_process(pid_t pid) {
    if (pid <= 0) return -1;
    int status = 0;
    pid_t result;
    do {
        result = waitpid(pid, &status, WNOHANG);
    } while (result < 0 && errno == EINTR);
    if (result == pid) {
        // Reap descendants while the leader PID is still owned by us.
        kill(-pid, SIGTERM);
        kill(-pid, SIGKILL);
        reap_process_group(pid);
        if (WIFEXITED(status)) return 2 + WEXITSTATUS(status);
        if (WIFSIGNALED(status)) return -2 - WTERMSIG(status);
        return 1;
    }
    if (result < 0 && (errno == ECHILD || errno == ESRCH)) return 1;
    if (result == 0) return 0;
    return -1;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStart(
    JNIEnv* env, jclass, jstring executable, jstring firmware, jint channel, jint usbFd, jint readerFd,
    jstring readerExecutable) {
    const std::string executablePath = stringFromJni(env, executable);
    const std::string firmwarePath = stringFromJni(env, firmware);
    const std::string readerExecutablePath = stringFromJni(env, readerExecutable);
    if (executablePath.empty() || firmwarePath.empty() || usbFd < 0 || channel < 13 || channel > 62 ||
        (readerFd >= 0 && readerExecutablePath.empty())) {
        return nullptr;
    }

    const long descriptorLimit = descriptor_limit();

    int tsPipe[2];
    int outPipe[2];
    int diagnosticPipe[2];
    if (pipe(outPipe) != 0) return nullptr;
    if (readerFd >= 0 && pipe(tsPipe) != 0) {
        close(outPipe[0]);
        close(outPipe[1]);
        return nullptr;
    }
    if (pipe(diagnosticPipe) != 0) {
        close(outPipe[0]);
        close(outPipe[1]);
        if (readerFd >= 0) {
            close(tsPipe[0]);
            close(tsPipe[1]);
        }
        return nullptr;
    }

    const int sianoStdout = readerFd >= 0 ? tsPipe[1] : outPipe[1];
    const pid_t siano = fork();
    if (siano < 0) {
        close(outPipe[0]);
        close(outPipe[1]);
        if (readerFd >= 0) {
            close(tsPipe[0]);
            close(tsPipe[1]);
        }
        close(diagnosticPipe[0]);
        close(diagnosticPipe[1]);
        return nullptr;
    }
    if (siano == 0) {
        // Do not leave a tuner process behind if the Android service dies.
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        if (getppid() == 1) _exit(127);
        setpgid(0, 0);
        if (dup2(usbFd, 3) < 0) {
            const int error = errno;
            dprintf(diagnosticPipe[1], "siano: dup2(usb): %s\n", strerror(error));
            _exit(127);
        }
        if (dup2(sianoStdout, STDOUT_FILENO) < 0) {
            const int error = errno;
            dprintf(diagnosticPipe[1], "siano: dup2(stdout): %s\n", strerror(error));
            _exit(127);
        }
        if (dup2(diagnosticPipe[1], STDERR_FILENO) < 0) {
            const int error = errno;
            dprintf(diagnosticPipe[1], "siano: dup2(stderr): %s\n", strerror(error));
            _exit(127);
        }
        close(outPipe[0]);
        close(diagnosticPipe[0]);
        if (sianoStdout != STDOUT_FILENO) close(sianoStdout);
        if (diagnosticPipe[1] != STDERR_FILENO) close(diagnosticPipe[1]);
        if (readerFd >= 0) {
            close(tsPipe[0]);
            if (tsPipe[1] != sianoStdout) close(tsPipe[1]);
        }
        if (usbFd != 3) close(usbFd);
        if (readerFd >= 0 && readerFd != 3) close(readerFd);
        // The Siano child only needs USB fd 3 plus stdio. Do not leak the
        // service's Binder/socket/pipe descriptors into the exec'd binary.
        close_inherited_descriptors(3);
        const std::string channelText = std::to_string(channel);
        char* const argv[] = {
            const_cast<char*>(executablePath.c_str()),
            const_cast<char*>("--channel"),
            const_cast<char*>(channelText.c_str()),
            const_cast<char*>("--firmware"),
            const_cast<char*>(firmwarePath.c_str()),
            const_cast<char*>("--fd"),
            const_cast<char*>("3"),
            nullptr,
        };
        dprintf(STDERR_FILENO, "siano: exec starting\n");
        execv(executablePath.c_str(), argv);
        const int error = errno;
        dprintf(STDERR_FILENO, "siano: exec %s: %s\n", executablePath.c_str(), strerror(error));
        _exit(127);
    }
    setpgid(siano, siano);

    if (readerFd >= 0) {
        const pid_t b25 = fork();
        if (b25 < 0) {
            kill(-siano, SIGTERM);
            waitpid(siano, nullptr, 0);
            close(outPipe[0]);
            close(outPipe[1]);
            close(tsPipe[0]);
            close(tsPipe[1]);
            close(diagnosticPipe[0]);
            close(diagnosticPipe[1]);
            return nullptr;
        }
        if (b25 == 0) {
            // Keep the B-CAS filter tied to the service lifetime as well.
            prctl(PR_SET_PDEATHSIG, SIGTERM);
            if (getppid() == 1) _exit(127);
            setpgid(0, siano);
            if (dup2(tsPipe[0], STDIN_FILENO) < 0 || dup2(outPipe[1], STDOUT_FILENO) < 0) _exit(127);
            if (readerFd != 4) {
                if (dup2(readerFd, 4) < 0) _exit(127);
            }
            const int readerFlags = fcntl(4, F_GETFD);
            if (readerFlags < 0 || fcntl(4, F_SETFD, readerFlags & ~FD_CLOEXEC) < 0) {
                _exit(127);
            }
            close(tsPipe[0]);
            close(tsPipe[1]);
            close(outPipe[0]);
            close(outPipe[1]);
            if (usbFd != 4) close(usbFd);
            if (readerFd != 4) close(readerFd);
            // The filter only needs card fd 4 plus stdio. In particular, do
            // not pass the Android service's descriptors through exec.
            close_inherited_descriptors_bounded(descriptorLimit, 4);
            char* const argv[] = {
                const_cast<char*>(readerExecutablePath.c_str()),
                const_cast<char*>("--reader-fd=4"),
                nullptr,
            };
            execv(readerExecutablePath.c_str(), argv);
            _exit(127);
        }
        setpgid(b25, siano);
        close(tsPipe[0]);
        close(tsPipe[1]);
        close(outPipe[1]);
    } else {
        close(outPipe[1]);
    }

    close(diagnosticPipe[1]);
    jint values[] = {outPipe[0], siano, diagnosticPipe[0]};
    jintArray result = env->NewIntArray(3);
    if (result == nullptr) {
        close(outPipe[0]);
        close(diagnosticPipe[0]);
        kill(-siano, SIGTERM);
        waitpid(-siano, nullptr, 0);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStop(JNIEnv*, jclass, jint pid) {
    if (pid <= 0) return;
    int observed_status = 0;
    pid_t observed;
    do {
        observed = waitpid(static_cast<pid_t>(pid), &observed_status, WNOHANG);
    } while (observed < 0 && errno == EINTR);
    // A poll may already have reaped this child. Never signal the numeric
    // PID/group as a whole after ownership is lost, since the PID could have
    // been reused. If this call itself reaped the leader, however, clean its
    // still-owned descendants before returning.
    if (observed == static_cast<pid_t>(pid)) {
        kill(-static_cast<pid_t>(pid), SIGTERM);
        kill(-static_cast<pid_t>(pid), SIGKILL);
        reap_process_group(static_cast<pid_t>(pid));
        return;
    }
    if (observed < 0 && (errno == ECHILD || errno == ESRCH)) return;
    if (observed < 0) return;
    kill(-static_cast<pid_t>(pid), SIGTERM);
    kill(static_cast<pid_t>(pid), SIGTERM);
    int status = 0;
    bool exited = false;
    for (int attempt = 0; attempt < 200; ++attempt) {
        const pid_t result = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
        if (result == static_cast<pid_t>(pid) || (result < 0 && errno == ECHILD)) {
            exited = true;
            break;
        }
        usleep(10 * 1000);
    }
    // Also kill descendants that ignored SIGTERM or outlived their parent.
    kill(-static_cast<pid_t>(pid), SIGKILL);
    if (!exited) {
        kill(static_cast<pid_t>(pid), SIGKILL);
        while (waitpid(static_cast<pid_t>(pid), &status, 0) < 0 && errno == EINTR) {}
    }
    for (;;) {
        const pid_t child = waitpid(-static_cast<pid_t>(pid), &status, 0);
        if (child > 0) continue;
        if (child < 0 && errno == EINTR) continue;
        break;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStartMirakc(
    JNIEnv* env, jclass, jstring executable, jstring config) {
    const std::string executablePath = stringFromJni(env, executable);
    const std::string configPath = stringFromJni(env, config);
    if (executablePath.empty() || configPath.empty()) return nullptr;

    // Keep the upstream process' bounded logs observable by the Android
    // service. A pipe is preferable to native logcat: Kotlin can cap each
    // line and retain the exact startup diagnostics alongside the exit code.
    int logPipe[2];
    if (pipe(logPipe) != 0) return nullptr;
    const pid_t mirakc = fork();
    if (mirakc < 0) {
        close(logPipe[0]);
        close(logPipe[1]);
        return nullptr;
    }
    if (mirakc == 0) {
        setpgid(0, 0);
        // Do not bind this child to the short-lived Kotlin startup worker:
        // Linux applies PDEATHSIG when that parent thread exits. Normal
        // shutdown uses NativeUsbProcess.stop() and process-group cleanup;
        // Android app teardown is handled by the process cgroup.
        // Preserve only the write end while dropping Binder/socket FDs from
        // the app process. Kotlin consumes this pipe on a daemon reader.
        close_inherited_descriptors(logPipe[1]);
        close(logPipe[0]);
        if (dup2(logPipe[1], STDOUT_FILENO) < 0 ||
            dup2(logPipe[1], STDERR_FILENO) < 0) {
            _exit(127);
        }
        if (logPipe[1] > STDERR_FILENO) close(logPipe[1]);
        const int nullFd = open("/dev/null", O_RDONLY);
        if (nullFd >= 0) {
            dup2(nullFd, STDIN_FILENO);
            if (nullFd > STDERR_FILENO) close(nullFd);
        }
        // All generated paths are absolute, but a stable app-private cwd
        // prevents an upstream relative fallback resolving under /data/local/tmp.
        const std::string::size_type slash = configPath.rfind('/');
        if (slash != std::string::npos && slash > 0) {
            const std::string directory = configPath.substr(0, slash);
            if (chdir(directory.c_str()) != 0) {
                dprintf(STDERR_FILENO, "mirakc: chdir(%s): %s\n", directory.c_str(), strerror(errno));
                _exit(127);
            }
        }
        execl(executablePath.c_str(), executablePath.c_str(), "--config", configPath.c_str(), nullptr);
        dprintf(STDERR_FILENO, "mirakc: exec %s: %s\n", executablePath.c_str(), strerror(errno));
        _exit(127);
    }
    setpgid(mirakc, mirakc);
    close(logPipe[1]);
    jint values[] = {logPipe[0], mirakc};
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(logPipe[0]);
        kill(-mirakc, SIGTERM);
        waitpid(mirakc, nullptr, 0);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativePollMirakc(JNIEnv*, jclass, jint pid) {
    return poll_process(static_cast<pid_t>(pid));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativePollSiano(JNIEnv*, jclass, jint pid) {
    return poll_process(static_cast<pid_t>(pid));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStartPx4d(
    JNIEnv* env, jclass, jstring executable, jstring firmware, jstring baseSerial,
    jstring runtimeDir, jint firstUsbFd, jint secondUsbFd) {
    const std::string executablePath = stringFromJni(env, executable);
    const std::string firmwarePath = stringFromJni(env, firmware);
    const std::string baseSerialValue = stringFromJni(env, baseSerial);
    const std::string runtimeDirPath = stringFromJni(env, runtimeDir);
    const bool single = secondUsbFd < 0;
    if (executablePath.empty() || firmwarePath.empty() || baseSerialValue.empty() ||
        runtimeDirPath.empty() || firstUsbFd < 0 ||
        (!single && (secondUsbFd < 0 || firstUsbFd == secondUsbFd))) {
        return nullptr;
    }

    int logPipe[2];
    if (pipe2(logPipe, O_CLOEXEC) != 0) return nullptr;
    const pid_t px4d = fork();
    if (px4d < 0) {
        close(logPipe[0]);
        close(logPipe[1]);
        return nullptr;
    }
    if (px4d == 0) {
        // Kotlin startup workers are intentionally short-lived. Android's
        // process cgroup and explicit nativeStop own teardown instead of a
        // thread-scoped PR_SET_PDEATHSIG.
        setpgid(0, 0);

        // The Android USB descriptors may already be numbered 3 or 4, and
        // the log pipe can occupy those numbers too. Duplicate every source
        // first so no dup2() target can overwrite a source still needed.
        const int first = duplicate_for_exec(firstUsbFd);
        const int second = single ? -1 : duplicate_for_exec(secondUsbFd);
        const int log = duplicate_for_exec(logPipe[1]);
        if (first < 0 || (!single && second < 0) || log < 0 || dup2(first, 3) < 0 ||
            (!single && dup2(second, 4) < 0) || dup2(log, STDOUT_FILENO) < 0 ||
            dup2(log, STDERR_FILENO) < 0) {
            dprintf(log >= 0 ? log : logPipe[1],
                    "px4d: unable to prepare USB descriptors: %s\n", strerror(errno));
            _exit(127);
        }
        close(logPipe[0]);
        if (logPipe[1] > STDERR_FILENO) close(logPipe[1]);
        if (log > STDERR_FILENO) close(log);
        if (first > STDERR_FILENO) close(first);
        if (!single && second > STDERR_FILENO && second != first) close(second);
        const int nullFd = open("/dev/null", O_RDONLY);
        if (nullFd >= 0) {
            dup2(nullFd, STDIN_FILENO);
            if (nullFd > STDERR_FILENO) close(nullFd);
        }
        // Keep only stdio and the USB descriptors px4d was given.
        close_inherited_descriptors(3, single ? -1 : 4);

        char* argv_storage[12];
        int argc = 0;
        argv_storage[argc++] = const_cast<char*>(executablePath.c_str());
        argv_storage[argc++] = const_cast<char*>("--fd");
        argv_storage[argc++] = const_cast<char*>("3");
        if (!single) {
            argv_storage[argc++] = const_cast<char*>("--fd");
            argv_storage[argc++] = const_cast<char*>("4");
        }
        argv_storage[argc++] = const_cast<char*>("--device");
        argv_storage[argc++] = const_cast<char*>(baseSerialValue.c_str());
        argv_storage[argc++] = const_cast<char*>("--firmware");
        argv_storage[argc++] = const_cast<char*>(firmwarePath.c_str());
        argv_storage[argc++] = const_cast<char*>("--runtime-dir");
        argv_storage[argc++] = const_cast<char*>(runtimeDirPath.c_str());
        argv_storage[argc] = nullptr;
        char* const* argv = argv_storage;
        dprintf(STDERR_FILENO, "px4d: exec starting\n");
        execv(executablePath.c_str(), argv);
        const int error = errno;
        dprintf(STDERR_FILENO, "px4d: exec %s: %s\n", executablePath.c_str(), strerror(error));
        _exit(127);
    }
    setpgid(px4d, px4d);
    close(logPipe[1]);
    jint values[] = {logPipe[0], px4d};
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(logPipe[0]);
        kill(-px4d, SIGTERM);
        waitpid(px4d, nullptr, 0);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativePollPx4d(JNIEnv*, jclass, jint pid) {
    return poll_process(static_cast<pid_t>(pid));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStopPx4d(JNIEnv*, jclass, jint pid) {
    if (pid <= 0) return -1;
    int status = 0;
    pid_t observed;
    do {
        // A waitable child is still owned by this service, so its PID cannot
        // be reused between this check and the signals below.  If it has
        // already been reaped, do not signal the numeric PID.
        observed = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
    } while (observed < 0 && errno == EINTR);
    if (observed == static_cast<pid_t>(pid)) return 0;
    if (observed < 0) return -1;

    kill(static_cast<pid_t>(pid), SIGTERM);
    for (int attempt = 0; attempt < 200; ++attempt) {
        do {
            observed = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
        } while (observed < 0 && errno == EINTR);
        if (observed == static_cast<pid_t>(pid) ||
            (observed < 0 && (errno == ECHILD || errno == ESRCH))) return 0;
        if (observed < 0) return -1;
        usleep(10 * 1000);
    }

    // The ownership check above remains valid until this child is reaped;
    // force-kill it only after the bounded graceful window has elapsed.
    kill(static_cast<pid_t>(pid), SIGKILL);
    do {
        observed = waitpid(static_cast<pid_t>(pid), &status, 0);
    } while (observed < 0 && errno == EINTR);
    return observed == static_cast<pid_t>(pid) ? 1 : -1;
}
