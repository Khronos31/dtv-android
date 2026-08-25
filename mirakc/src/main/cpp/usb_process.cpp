#include <jni.h>

#include <cerrno>
#include <csignal>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>

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

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStart(
    JNIEnv* env, jclass, jstring executable, jstring firmware, jint channel, jint usbFd) {
    const std::string executablePath = stringFromJni(env, executable);
    const std::string firmwarePath = stringFromJni(env, firmware);
    if (executablePath.empty() || firmwarePath.empty() || usbFd < 0 || channel < 13 || channel > 62) {
        return nullptr;
    }

    int outputPipe[2];
    if (pipe(outputPipe) != 0) {
        return nullptr;
    }

    const pid_t child = fork();
    if (child < 0) {
        const int error = errno;
        close(outputPipe[0]);
        close(outputPipe[1]);
        return nullptr;
    }
    if (child == 0) {
        if (dup2(usbFd, 3) < 0 || dup2(outputPipe[1], STDOUT_FILENO) < 0) {
            _exit(127);
        }
        close(outputPipe[0]);
        close(outputPipe[1]);
        if (usbFd != 3) {
            close(usbFd);
        }

        const int nullFd = open("/dev/null", O_WRONLY);
        if (nullFd >= 0) {
            dup2(nullFd, STDERR_FILENO);
            if (nullFd != STDERR_FILENO) {
                close(nullFd);
            }
        }

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

    close(outputPipe[1]);
    jint values[] = {outputPipe[0], child};
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(outputPipe[0]);
        kill(child, SIGTERM);
        waitpid(child, nullptr, 0);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_khronos31_mirakc_NativeUsbProcess_nativeStop(JNIEnv*, jclass, jint pid) {
    if (pid <= 0) {
        return;
    }
    kill(static_cast<pid_t>(pid), SIGTERM);
    int status = 0;
    while (waitpid(static_cast<pid_t>(pid), &status, 0) < 0 && errno == EINTR) {
    }
}
