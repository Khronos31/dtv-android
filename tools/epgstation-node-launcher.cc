#include <dlfcn.h>
#include <node.h>
#include <cstdio>

int main(int argc, char** argv) {
    // Android's linker does not put DT_NEEDED symbols of the main
    // executable into the global namespace. Node addons (sqlite3,
    // @node-rs/crc32) resolve napi_* from libnode via RTLD_GLOBAL.
    if (dlopen("libnode.so", RTLD_NOW | RTLD_GLOBAL) == nullptr) {
        std::fprintf(stderr, "dlopen libnode.so: %s\n", dlerror());
        return 127;
    }
    dlopen("libc++_shared.so", RTLD_NOW | RTLD_GLOBAL);
    return node::Start(argc, argv);
}
