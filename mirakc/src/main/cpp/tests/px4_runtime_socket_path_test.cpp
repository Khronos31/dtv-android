#include "px4/error.h"
#include "px4/posix_ipc.h"

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <string>
#include <vector>

namespace {

constexpr const char* kAndroidFilesDir =
    "/data/user/0/dev.khronos31.mirakc/files";
constexpr const char* kRuntimeRoot =
    "/data/user/0/dev.khronos31.mirakc/files/p4";

struct Enclosure final {
    const char* model;
    const char* instance;
};

bool path_fits(const char* instance, const char* endpoint)
{
    const std::string path = std::string(kRuntimeRoot) + "/px4-userland/" +
                             instance + "/" + endpoint;
    if (path.size() + 1U > sizeof(sockaddr_un{}.sun_path)) {
        std::fprintf(stderr, "%s endpoint path too long (%zu bytes): %s\n",
                     endpoint, path.size() + 1U, path.c_str());
        return false;
    }

    const px4::userland::ipc::posix::EndpointConfig config{
        kRuntimeRoot, instance, endpoint,
        px4::userland::ipc::posix::EndpointAccess::private_user};
    const auto listener =
        px4::userland::ipc::posix::SocketListener::listen(config);
    if (listener || listener.error() != px4::userland::Error::NOT_FOUND) {
        std::fprintf(stderr,
                     "%s %s was not accepted by pinned make_layout (error=%s)\n",
                     instance, endpoint,
                     px4::userland::error_string(listener.error()));
        return false;
    }
    return true;
}

}  // namespace

int main()
{
    // This host proof intentionally uses the Android app path while ensuring
    // the real host filesystem is never created or modified by listen().
    struct stat status {};
    if (::lstat(kAndroidFilesDir, &status) == 0 || errno != ENOENT) {
        std::fprintf(stderr,
                     "refusing host proof because Android filesDir exists: %s\n",
                     kAndroidFilesDir);
        return 2;
    }

    const std::vector<Enclosure> enclosures{
        {"Q3U4", "px4-q3u4-84a-12345678901234"},
        {"MLT5", "px4-mlt5-24e-987654321098765"},
        {"M1UR", "px4-m1ur-854-123456789012345"},
        {"S1UR", "px4-s1ur-855-123456789012345"},
        {"MLT5-second", "px4-mlt5-924e-000000000000001"},
    };
    const char* const endpoints[]{"control.sock", "stream.sock"};
    for (const auto& enclosure : enclosures) {
        for (const char* endpoint : endpoints) {
            if (!path_fits(enclosure.instance, endpoint)) return 1;
        }
    }

    std::puts("pinned px4-userland Android socket path proof: PASS");
    return 0;
}
