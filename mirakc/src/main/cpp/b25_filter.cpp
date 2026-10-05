#include <unistd.h>
#include <android/log.h>
#include <cstdint>

#include "arib_std_b25.h"
#include "b_cas_card.h"
#include "ccid_reader.h"

#define LOG_TAG "b25-filter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using B25InputObserver = void (*)(void*, std::uint64_t);

static void observe_input_once(B25InputObserver observer,
                               void* observer_context,
                               bool* observed_input,
                               ssize_t bytes) {
    if (observer == nullptr || observer_context == nullptr || observed_input == nullptr ||
        *observed_input || bytes <= 0) {
        return;
    }
    *observed_input = true;
    observer(observer_context, static_cast<std::uint64_t>(bytes));
}

static int passthrough_loop(std::uint64_t* input_bytes,
                            B25InputObserver observer,
                            void* observer_context,
                            bool* observed_input) {
    uint8_t copy[32 * 1024];
    while (true) {
        ssize_t n = read(STDIN_FILENO, copy, sizeof(copy));
        if (n <= 0) break;
        if (input_bytes != nullptr) *input_bytes += static_cast<std::uint64_t>(n);
        observe_input_once(observer, observer_context, observed_input, n);
        if (write(STDOUT_FILENO, copy, (size_t)n) < 0) break;
    }
    return 1;
}

extern "C" int b25_stdio_filter_with_card(B_CAS_CARD *bcas,
                                            std::uint64_t* input_bytes,
                                            B25InputObserver observer,
                                            void* observer_context) {
    if (input_bytes != nullptr) *input_bytes = 0U;
    bool observed_input = false;
    if (bcas == nullptr || bcas->init(bcas) != 0) {
        LOGE("B-CAS init failed, passing TS through");
        if (bcas) bcas->release(bcas);
        return passthrough_loop(input_bytes, observer, observer_context, &observed_input);
    }

    ARIB_STD_B25 *b25 = create_arib_std_b25();
    if (b25 == nullptr) {
        bcas->release(bcas);
        return passthrough_loop(input_bytes, observer, observer_context, &observed_input);
    }
    b25->set_multi2_round(b25, 4);
    b25->set_strip(b25, 0);
    b25->set_emm_proc(b25, 1);
    if (b25->set_b_cas_card(b25, bcas) != 0) {
        LOGE("set_b_cas_card failed");
        b25->release(b25);
        bcas->release(bcas);
        return passthrough_loop(input_bytes, observer, observer_context, &observed_input);
    }
    LOGI("B25 decoder ready");

    uint8_t buf[64 * 1024];
    while (true) {
        ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
        if (n < 0) break;
        if (n == 0) break;
        if (input_bytes != nullptr) *input_bytes += static_cast<std::uint64_t>(n);
        observe_input_once(observer, observer_context, &observed_input, n);
        ARIB_STD_B25_BUFFER sbuf;
        sbuf.data = buf;
        sbuf.size = (int32_t)n;
        if (b25->put(b25, &sbuf) < 0) {
            if (write(STDOUT_FILENO, buf, (size_t)n) < 0) break;
            continue;
        }
        ARIB_STD_B25_BUFFER dbuf;
        if (b25->get(b25, &dbuf) == 0 && dbuf.size > 0 && dbuf.data != nullptr) {
            if (write(STDOUT_FILENO, dbuf.data, (size_t)dbuf.size) < 0) break;
        }
    }
    b25->flush(b25);
    {
        ARIB_STD_B25_BUFFER dbuf;
        if (b25->get(b25, &dbuf) == 0 && dbuf.size > 0 && dbuf.data != nullptr) {
            write(STDOUT_FILENO, dbuf.data, (size_t)dbuf.size);
        }
    }
    b25->release(b25);
    bcas->release(bcas);
    return 0;
}

extern "C" int b25_stdio_filter(int reader_fd) {
    if (ccid_open(reader_fd) != 0) {
        LOGE("ccid_open failed, passing TS through");
        return passthrough_loop(nullptr, nullptr, nullptr, nullptr);
    }
    B_CAS_CARD *bcas = create_b_cas_card();
    const int result = b25_stdio_filter_with_card(bcas, nullptr, nullptr, nullptr);
    if (bcas == nullptr) ccid_close();
    return result;
}
