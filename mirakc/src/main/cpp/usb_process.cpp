#include <jni.h>

#include <cerrno>
#include <csignal>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstring>
#include <string>

extern "C" int b25_stdio_filter(int reader_fd);

namespace {

std::string stringFromJni(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

void close_inherited_descriptors(int preserved_fd = -1) {
    long limit = sysconf(_SC_OPEN_MAX);
    if (limit < 0 || limit > 65536) limit = 65536;
    for (int fd = STDERR_FILENO + 1; fd < limit; ++fd) {
        if (fd != preserved_fd) close(fd);
    }
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

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStart(
    JNIEnv* env, jclass, jstring executable, jstring firmware, jint channel, jint usbFd, jint readerFd) {
    const std::string executablePath = stringFromJni(env, executable);
    const std::string firmwarePath = stringFromJni(env, firmware);
    if (executablePath.empty() || firmwarePath.empty() || usbFd < 0 || channel < 13 || channel > 62) {
        return nullptr;
    }

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
        if (dup2(usbFd, 3) < 0 || dup2(sianoStdout, STDOUT_FILENO) < 0 ||
            dup2(diagnosticPipe[1], STDERR_FILENO) < 0) _exit(127);
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
        execv(executablePath.c_str(), argv);
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
            int cardFd = readerFd;
            if (readerFd != 4) {
                if (dup2(readerFd, 4) < 0) _exit(127);
                cardFd = 4;
            }
            close(tsPipe[0]);
            close(tsPipe[1]);
            close(outPipe[0]);
            close(outPipe[1]);
            if (usbFd != 4) close(usbFd);
            if (readerFd != 4) close(readerFd);
            // The filter only needs card fd 4 plus stdio. In particular, do
            // not pass the Android service's descriptors through exec.
            close_inherited_descriptors(4);
            b25_stdio_filter(cardFd);
            _exit(0);
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
        // If the Android service process dies, do not leave the server behind.
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        if (getppid() == 1) _exit(127);
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
    if (pid <= 0) return -1;
    int status = 0;
    pid_t result;
    do {
        result = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
    } while (result < 0 && errno == EINTR);
    if (result == static_cast<pid_t>(pid)) {
        // Clean descendants immediately after collecting this leader's exit;
        // nativeStop must not later signal a reused numeric PID.
        kill(-static_cast<pid_t>(pid), SIGTERM);
        kill(-static_cast<pid_t>(pid), SIGKILL);
        reap_process_group(static_cast<pid_t>(pid));
        if (WIFEXITED(status)) return 2 + WEXITSTATUS(status);
        if (WIFSIGNALED(status)) return -2 - WTERMSIG(status);
        return 1;
    }
    if (result < 0 && (errno == ECHILD || errno == ESRCH)) return 1;
    if (result == 0) return 0;
    return -1;
}
