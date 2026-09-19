#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>

extern "C" int b25_stdio_filter(int reader_fd);

namespace {

int parse_reader_fd(const char* value) {
    if (value == nullptr || *value == '\0') return -1;
    errno = 0;
    char* end = nullptr;
    const long parsed = std::strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || parsed < 0 || parsed > INT_MAX) return -1;
    return static_cast<int>(parsed);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 2 || std::strncmp(argv[1], "--reader-fd=", 12) != 0) return 64;
    const int reader_fd = parse_reader_fd(argv[1] + 12);
    if (reader_fd < 0) return 64;
    return b25_stdio_filter(reader_fd);
}
