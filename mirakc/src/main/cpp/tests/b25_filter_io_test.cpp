#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <unistd.h>

#include "arib_std_b25.h"
#include "b_cas_card_error_code.h"
#include "b25_filter_contract.h"

extern "C" int b25_stdio_filter_with_card(B_CAS_CARD* bcas,
                                            std::uint64_t* input_bytes,
                                            void (*observer)(void*, std::uint64_t),
                                            void* observer_context);
extern "C" int b25_stdio_filter(int reader_fd);

namespace {

struct IoAction {
    ssize_t result;
    int error;
};

std::vector<IoAction> read_actions;
std::vector<IoAction> write_actions;
std::string input;
std::string output;
std::size_t input_offset = 0;
std::size_t read_action = 0;
std::size_t write_action = 0;
int init_result = B_CAS_CARD_ERROR_ALL_READERS_CONNECTION_FAILED;
int put_result = 0;
int get_result = 0;
int flush_result = 0;
int release_count = 0;
int get_count = 0;
bool decoder_creation_failure = false;
int ccid_open_result = 0;
int observer_calls = 0;
std::string decoder_output;

void reset() {
    read_actions.clear();
    write_actions.clear();
    input.clear();
    output.clear();
    input_offset = 0;
    read_action = 0;
    write_action = 0;
    init_result = B_CAS_CARD_ERROR_ALL_READERS_CONNECTION_FAILED;
    put_result = 0;
    get_result = 0;
    flush_result = 0;
    release_count = 0;
    get_count = 0;
    decoder_creation_failure = false;
    ccid_open_result = 0;
    observer_calls = 0;
    decoder_output.clear();
}

int card_init(void*) { return init_result; }
void card_release(void*) { ++release_count; }
void decoder_release(void*) {}
int decoder_set_int(void*, int) { return 0; }
int decoder_set_card(void*, B_CAS_CARD*) { return 0; }
int decoder_flush(void*) { return flush_result; }
int decoder_put(void*, ARIB_STD_B25_BUFFER*) { return put_result; }
int decoder_get(void*, ARIB_STD_B25_BUFFER* buffer) {
    ++get_count;
    if (get_result != 0) return get_result;
    const size_t separator = decoder_output.find('|');
    const size_t start = get_count == 1 || separator == std::string::npos ? 0 : separator + 1;
    const size_t count = separator == std::string::npos
        ? decoder_output.size()
        : (get_count == 1 ? separator : decoder_output.size() - start);
    buffer->data = reinterpret_cast<std::uint8_t*>(decoder_output.data() + start);
    buffer->size = static_cast<int32_t>(count);
    return 0;
}

B_CAS_CARD card{};
ARIB_STD_B25 decoder{};
void configure_decoder();

void configure_card() {
    card = {};
    card.init = card_init;
    card.release = card_release;
    configure_decoder();
}

void configure_decoder() {
    decoder = {};
    decoder.release = decoder_release;
    decoder.set_multi2_round = decoder_set_int;
    decoder.set_strip = decoder_set_int;
    decoder.set_emm_proc = decoder_set_int;
    decoder.set_b_cas_card = decoder_set_card;
    decoder.flush = decoder_flush;
    decoder.put = decoder_put;
    decoder.get = decoder_get;
}

bool expect(bool condition, const char* label) {
    if (condition) return true;
    std::fprintf(stderr, "B25 host test failed: %s\n", label);
    return false;
}

void observe_first_data(void* context, std::uint64_t bytes) {
    ++observer_calls;
    if (context != nullptr) *static_cast<std::uint64_t*>(context) = bytes;
}

}  // namespace

extern "C" ssize_t b25_test_read(int, void* buffer, size_t size) {
    if (read_action < read_actions.size()) {
        const IoAction action = read_actions[read_action++];
        if (action.result < 0) {
            errno = action.error;
            return -1;
        }
        if (action.result == 0) return 0;
        const size_t count = static_cast<size_t>(action.result);
        std::memcpy(buffer, input.data() + input_offset, count);
        input_offset += count;
        return static_cast<ssize_t>(count);
    }
    if (input_offset == input.size()) return 0;
    const size_t count = size < input.size() - input_offset ? size : input.size() - input_offset;
    std::memcpy(buffer, input.data() + input_offset, count);
    input_offset += count;
    return static_cast<ssize_t>(count);
}

extern "C" ssize_t b25_test_write(int, const void* buffer, size_t size) {
    if (write_action < write_actions.size()) {
        const IoAction action = write_actions[write_action++];
        if (action.result <= 0) {
            if (action.result < 0) errno = action.error;
            return action.result;
        }
        const size_t count = static_cast<size_t>(action.result);
        output.append(static_cast<const char*>(buffer), count);
        return static_cast<ssize_t>(count);
    }
    output.append(static_cast<const char*>(buffer), size);
    return static_cast<ssize_t>(size);
}

extern "C" int ccid_open(int) { return ccid_open_result; }
extern "C" void ccid_close(void) {}
extern "C" B_CAS_CARD* create_b_cas_card() { return &card; }
extern "C" ARIB_STD_B25* create_arib_std_b25() {
    return decoder_creation_failure ? nullptr : &decoder;
}
int __android_log_print(int, const char*, const char*, ...) { return 0; }

int main() {
    bool ok = true;

    reset();
    configure_card();
    input = "0123456789";
    read_actions = {{-1, EINTR}, {3, 0}};
    write_actions = {{2, 0}, {-1, EINTR}};
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_OK,
                 "no-card EOF is success");
    ok &= expect(output == input, "short write and EINTR preserve passthrough bytes");

    reset();
    configure_card();
    input = "unencrypted";
    init_result = B_CAS_CARD_ERROR_TRANSMIT_FAILED;
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_OK,
                 "B-CAS initial transport failure retains passthrough mode");
    ok &= expect(output == input, "B-CAS transport fallback forwards the original bytes once");

    reset();
    configure_card();
    input = "firstlater";
    read_actions = {{5, 0}};
    std::uint64_t first_data_bytes = 0;
    std::uint64_t total_input_bytes = 0;
    ok &= expect(b25_stdio_filter_with_card(&card, &total_input_bytes, observe_first_data,
                                            &first_data_bytes) == B25_FILTER_OK,
                 "observed passthrough normal EOF is success");
    ok &= expect(output == input && observer_calls == 1 && first_data_bytes == 5 &&
                     total_input_bytes == input.size(),
                 "input observer remains one-shot and input byte count remains complete");

    reset();
    configure_card();
    input = "input";
    init_result = B_CAS_CARD_ERROR_NO_ENOUGH_MEMORY;
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_SETUP_ERROR,
                 "B-CAS setup failure is not treated as no-card passthrough");
    ok &= expect(output.empty(), "B-CAS setup failure does not pass unprocessed input through");

    reset();
    ok &= expect(b25_stdio_filter_with_card(nullptr, nullptr, nullptr, nullptr) == B25_FILTER_SETUP_ERROR,
                 "missing B-CAS object is a setup failure");

    reset();
    configure_card();
    input = "data";
    write_actions = {{0, 0}};
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_IO_ERROR,
                 "zero-progress output is an I/O error");

    reset();
    configure_card();
    read_actions = {{-1, EIO}};
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_IO_ERROR,
                 "passthrough read failure is reported");

    reset();
    configure_card();
    input = "input";
    init_result = 0;
    decoder_output = "decoded|tail";
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_OK,
                 "decoder normal EOF is success");
    ok &= expect(output == "decodedtail", "decoder and flush output are written once");

    reset();
    configure_card();
    input = "input";
    init_result = 0;
    flush_result = 1;
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_OK,
                 "libarib25 positive flush warning is not treated as failure");

    reset();
    configure_card();
    input = "input";
    init_result = 0;
    decoder_output = "decoded|tail";
    write_actions = {{-1, EPIPE}};
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_IO_ERROR,
                 "downstream disconnect is an I/O error");

    reset();
    configure_card();
    input = "input";
    init_result = 0;
    decoder_output = "decoded|tail";
    write_actions = {{7, 0}, {-1, EPIPE}};
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_IO_ERROR,
                 "flush output disconnect is reported");

    reset();
    configure_card();
    input = "raw";
    init_result = 0;
    put_result = -1;
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_DECODER_ERROR,
                 "put failure is not hidden by raw fallback");
    ok &= expect(output.empty(), "put failure does not duplicate undecoded input");

    reset();
    configure_card();
    decoder_creation_failure = true;
    init_result = 0;
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_SETUP_ERROR,
                 "decoder setup failure is distinct from no-card passthrough");

    reset();
    configure_card();
    ccid_open_result = -1;
    ok &= expect(b25_stdio_filter(1) == B25_FILTER_SETUP_ERROR,
                 "CCID setup failure is not reported as no-card EOF");

    reset();
    configure_card();
    input = "input";
    init_result = 0;
    get_result = -1;
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_DECODER_ERROR,
                 "get failure is reported");

    reset();
    configure_card();
    input = "input";
    init_result = 0;
    flush_result = -1;
    ok &= expect(b25_stdio_filter_with_card(&card, nullptr, nullptr, nullptr) == B25_FILTER_DECODER_ERROR,
                 "flush failure is reported");

    if (!ok) return 1;
    std::puts("B25 I/O host fixtures: PASS");
    return 0;
}
