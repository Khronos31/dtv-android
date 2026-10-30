#include <dtv/userland/host_runtime/control_socket.h>

#if defined(MIRAKC_HAS_ASICEN_LEGACY_PATH)
#include "px4/posix_ipc.h"
#endif

#include <cerrno>
#include <cstdio>
#include <string>
#include <vector>
#include <sys/stat.h>

namespace {

constexpr const char* kAndroidFilesDir =
    "/data/user/0/dev.khronos31.mirakc/files";
constexpr const char* kPx4Root =
    "/data/user/0/dev.khronos31.mirakc/files/p4";
constexpr const char* kAsicenRoot =
    "/data/user/0/dev.khronos31.mirakc/files/a1";
constexpr const char* kPixRoot =
    "/data/user/0/dev.khronos31.mirakc/files/x1";
constexpr const char* kAsicenInstance = "asicen-0123456789ab";
constexpr const char* kPixInstance = "pix-0123456789ab";

struct Enclosure final {
    const char* runtime;
    const char* product;
    const char* instance;
};

bool path_fits(const char* runtime, const char* product,
               const char* instance, const char* endpoint)
{
    const dtv::userland::host_runtime::EndpointConfig config{
        runtime, product, instance,
        dtv::userland::host_runtime::EndpointAccess::private_user,
        -1, endpoint};
    const auto path = dtv::userland::host_runtime::endpoint_path(config);
    const std::string expected = std::string(runtime) + "/" + product +
                                 "/v1/" + instance + "/" + endpoint;
    if (!path || path.value() != expected) {
        std::fprintf(stderr, "%s %s path did not match Core v1 layout\n",
                     instance, endpoint);
        return false;
    }
    if (path.value().size() + 1U > 108U) {
        std::fprintf(stderr, "%s endpoint path exceeds Android sun_path: %zu bytes\n",
                     endpoint, path.value().size() + 1U);
        return false;
    }
    return true;
}

bool old_asicen_layout_rejected()
{
    const std::string duplicated_runtime =
        std::string(kAsicenRoot) + "/" + kAsicenInstance;
    const dtv::userland::host_runtime::EndpointConfig config{
        duplicated_runtime, "asicen-userland", kAsicenInstance,
        dtv::userland::host_runtime::EndpointAccess::private_user,
        -1, "control.sock"};
    dtv::userland::host_runtime::EndpointDiagnostic diagnostic{};
    const auto path = dtv::userland::host_runtime::endpoint_path(config, &diagnostic);
    if (path || diagnostic.reason !=
                    dtv::userland::host_runtime::EndpointDiagnosticReason::path_too_long ||
        diagnostic.required_bytes <= diagnostic.native_capacity) {
        std::fprintf(stderr, "Core accepted the old duplicated ASICEN runtime path\n");
        return false;
    }
    return true;
}

#if defined(MIRAKC_HAS_ASICEN_LEGACY_PATH)
bool legacy_asicen_layout()
{
    struct stat status {};
    if (::lstat(kAndroidFilesDir, &status) == 0 || errno != ENOENT) {
        std::fprintf(stderr, "refusing legacy lease proof because Android files dir exists\n");
        return false;
    }

    const std::string duplicated_runtime =
        std::string(kAsicenRoot) + "/" + kAsicenInstance;
    const px4::userland::ipc::posix::EndpointConfig old_config{
        duplicated_runtime.c_str(), kAsicenInstance,
        px4::userland::ipc::posix::kControlEndpointName,
        px4::userland::ipc::posix::EndpointAccess::private_user};
    px4::userland::ipc::posix::EndpointDiagnostic old_diagnostic{};
    const auto old_lease =
        px4::userland::ipc::posix::SerialEndpointLease::acquire_identity(
            old_config, kAsicenInstance, &old_diagnostic);
    if (old_lease || old_diagnostic.reason !=
                         px4::userland::ipc::posix::EndpointDiagnosticReason::path_too_long ||
        old_diagnostic.required_bytes <= old_diagnostic.native_capacity) {
        std::fprintf(stderr, "legacy ASICEN lease accepted the duplicated runtime path\n");
        return false;
    }

    const px4::userland::ipc::posix::EndpointConfig shared_config{
        kAsicenRoot, kAsicenInstance,
        px4::userland::ipc::posix::kControlEndpointName,
        px4::userland::ipc::posix::EndpointAccess::private_user};
    px4::userland::ipc::posix::EndpointDiagnostic shared_diagnostic{};
    const auto shared_lease =
        px4::userland::ipc::posix::SerialEndpointLease::acquire_identity(
            shared_config, kAsicenInstance, &shared_diagnostic);
    if (shared_lease ||
        shared_diagnostic.reason ==
            px4::userland::ipc::posix::EndpointDiagnosticReason::path_too_long ||
        shared_diagnostic.required_bytes > shared_diagnostic.native_capacity) {
        std::fprintf(stderr, "legacy ASICEN shared runtime path did not fit\n");
        return false;
    }
    return true;
}
#endif

}  // namespace

int main()
{
    const std::vector<Enclosure> enclosures{
        {kPx4Root, "px4-userland", "px4-q3u4-84a-12345678901234"},
        {kPx4Root, "px4-userland", "px4-mlt5-24e-987654321098765"},
        {kPx4Root, "px4-userland", "px4-m1ur-854-123456789012345"},
        {kPx4Root, "px4-userland", "px4-s1ur-855-123456789012345"},
        {kPx4Root, "px4-userland", "px4-mlt5-924e-000000000000001"},
        {kAsicenRoot, "asicen-userland", kAsicenInstance},
        {kPixRoot, "pix-userland", kPixInstance},
    };
    const char* const endpoints[]{"control.sock", "stream.sock"};
    for (const auto& enclosure : enclosures) {
        for (const char* endpoint : endpoints) {
            if (!path_fits(enclosure.runtime, enclosure.product,
                           enclosure.instance, endpoint)) return 1;
        }
    }

    if (!old_asicen_layout_rejected()) return 1;
#if defined(MIRAKC_HAS_ASICEN_LEGACY_PATH)
    if (!legacy_asicen_layout()) return 1;
#endif

    const dtv::userland::host_runtime::EndpointConfig long_config{
        kPx4Root, "px4-userland",
        "px4-q3u4-84a-12345678901234-extra-long-instance-name",
        dtv::userland::host_runtime::EndpointAccess::private_user,
        -1, "control.sock"};
    if (dtv::userland::host_runtime::endpoint_path(long_config)) {
        std::fprintf(stderr, "Core accepted an overlong Android endpoint path\n");
        return 1;
    }

    std::puts("pinned Core Android socket path proof: PASS");
    return 0;
}
