#include <unistd.h>
#include <android/log.h>
#include <cstdint>
#include <cerrno>

#include "arib_std_b25.h"
#include "b_cas_card.h"
#include "b_cas_card_error_code.h"
#include "ccid_reader.h"
#include "b25_filter_contract.h"

#define LOG_TAG "b25-filter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using B25InputObserver = void (*)(void*, std::uint64_t);

static bool write_all(int fd, const uint8_t* data, size_t size) {
    while (size > 0) {
        const ssize_t written = write(fd, data, size);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        data += written;
        size -= static_cast<size_t>(written);
    }
    return true;
}

static ssize_t read_retry(int fd, uint8_t* data, size_t size) {
    ssize_t count;
    do {
        count = read(fd, data, size);
    } while (count < 0 && errno == EINTR);
    return count;
}

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
        ssize_t n = read_retry(STDIN_FILENO, copy, sizeof(copy));
        if (n == 0) return B25_FILTER_OK;
        if (n < 0) return B25_FILTER_IO_ERROR;
        if (input_bytes != nullptr) *input_bytes += static_cast<std::uint64_t>(n);
        observe_input_once(observer, observer_context, observed_input, n);
        if (!write_all(STDOUT_FILENO, copy, static_cast<size_t>(n))) {
            return B25_FILTER_IO_ERROR;
        }
    }
}

extern "C" int b25_stdio_filter_with_card(B_CAS_CARD *bcas,
                                            std::uint64_t* input_bytes,
                                            B25InputObserver observer,
                                            void* observer_context) {
    if (input_bytes != nullptr) *input_bytes = 0U;
    bool observed_input = false;
    if (bcas == nullptr) {
        LOGE("B-CAS setup unavailable");
        return B25_FILTER_SETUP_ERROR;
    }
    const int card_result = bcas->init(bcas);
    if (card_result != 0) {
        bcas->release(bcas);
        if (card_result == B_CAS_CARD_ERROR_ALL_READERS_CONNECTION_FAILED ||
            card_result == B_CAS_CARD_ERROR_TRANSMIT_FAILED) {
            // Keep the existing unencrypted pass-through for card transport failures.
            LOGE("B-CAS unavailable, passing TS through");
            return passthrough_loop(input_bytes, observer, observer_context, &observed_input);
        }
        LOGE("B-CAS setup failed");
        return B25_FILTER_SETUP_ERROR;
    }

    ARIB_STD_B25 *b25 = create_arib_std_b25();
    if (b25 == nullptr) {
        bcas->release(bcas);
        return B25_FILTER_SETUP_ERROR;
    }
    if (b25->set_multi2_round(b25, 4) != 0 ||
        b25->set_strip(b25, 0) != 0 ||
        b25->set_emm_proc(b25, 1) != 0 ||
        b25->set_b_cas_card(b25, bcas) != 0) {
        LOGE("B25 setup failed");
        b25->release(b25);
        bcas->release(bcas);
        return B25_FILTER_SETUP_ERROR;
    }
    LOGI("B25 decoder ready");

    uint8_t buf[64 * 1024];
    int result = B25_FILTER_OK;
    while (true) {
        ssize_t n = read_retry(STDIN_FILENO, buf, sizeof(buf));
        if (n < 0) {
            result = B25_FILTER_IO_ERROR;
            break;
        }
        if (n == 0) break;
        if (input_bytes != nullptr) *input_bytes += static_cast<std::uint64_t>(n);
        observe_input_once(observer, observer_context, &observed_input, n);
        ARIB_STD_B25_BUFFER sbuf;
        sbuf.data = buf;
        sbuf.size = (int32_t)n;
        if (b25->put(b25, &sbuf) < 0) {
            result = B25_FILTER_DECODER_ERROR;
            break;
        }
        ARIB_STD_B25_BUFFER dbuf;
        if (b25->get(b25, &dbuf) != 0) {
            result = B25_FILTER_DECODER_ERROR;
            break;
        }
        if (dbuf.size < 0 || (dbuf.size > 0 && dbuf.data == nullptr)) {
            result = B25_FILTER_DECODER_ERROR;
            break;
        }
        if (dbuf.size > 0 &&
            !write_all(STDOUT_FILENO, dbuf.data, static_cast<size_t>(dbuf.size))) {
            result = B25_FILTER_IO_ERROR;
            break;
        }
    }
    if (result == B25_FILTER_OK && b25->flush(b25) < 0) {
        result = B25_FILTER_DECODER_ERROR;
    }
    if (result == B25_FILTER_OK) {
        ARIB_STD_B25_BUFFER dbuf;
        if (b25->get(b25, &dbuf) != 0 || dbuf.size < 0 ||
            (dbuf.size > 0 && dbuf.data == nullptr)) {
            result = B25_FILTER_DECODER_ERROR;
        } else if (dbuf.size > 0 &&
                   !write_all(STDOUT_FILENO, dbuf.data, static_cast<size_t>(dbuf.size))) {
            result = B25_FILTER_IO_ERROR;
        }
    }
    b25->release(b25);
    bcas->release(bcas);
    return result;
}

extern "C" int b25_stdio_filter(int reader_fd) {
    if (ccid_open(reader_fd) != 0) {
        LOGE("ccid setup failed");
        ccid_close();
        return B25_FILTER_SETUP_ERROR;
    }
    B_CAS_CARD *bcas = create_b_cas_card();
    if (bcas == nullptr) {
        ccid_close();
        return B25_FILTER_SETUP_ERROR;
    }
    const int result = b25_stdio_filter_with_card(bcas, nullptr, nullptr, nullptr);
    ccid_close();
    return result;
}
