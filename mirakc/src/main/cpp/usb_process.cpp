#include <jni.h>

#include <cerrno>
#include <csignal>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>

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

void redirect_stderr_null() {
    const int nullFd = open("/dev/null", O_WRONLY);
    if (nullFd >= 0) {
        dup2(nullFd, STDERR_FILENO);
        if (nullFd != STDERR_FILENO) close(nullFd);
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
    if (pipe(outPipe) != 0) return nullptr;
    if (readerFd >= 0 && pipe(tsPipe) != 0) {
        close(outPipe[0]);
        close(outPipe[1]);
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
        return nullptr;
    }
    if (siano == 0) {
        setpgid(0, 0);
        if (dup2(usbFd, 3) < 0 || dup2(sianoStdout, STDOUT_FILENO) < 0) _exit(127);
        close(outPipe[0]);
        if (sianoStdout != STDOUT_FILENO) close(sianoStdout);
        if (readerFd >= 0) {
            close(tsPipe[0]);
            if (tsPipe[1] != sianoStdout) close(tsPipe[1]);
        }
        if (usbFd != 3) close(usbFd);
        if (readerFd >= 0 && readerFd != 3) close(readerFd);
        redirect_stderr_null();
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
            return nullptr;
        }
        if (b25 == 0) {
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

    jint values[] = {outPipe[0], siano};
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(outPipe[0]);
        kill(-siano, SIGTERM);
        waitpid(-siano, nullptr, 0);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStop(JNIEnv*, jclass, jint pid) {
    if (pid <= 0) return;
    kill(-static_cast<pid_t>(pid), SIGTERM);
    kill(static_cast<pid_t>(pid), SIGTERM);
    int status = 0;
    while (waitpid(-static_cast<pid_t>(pid), &status, 0) > 0) {
    }
    while (waitpid(static_cast<pid_t>(pid), &status, 0) < 0 && errno == EINTR) {
    }
}
