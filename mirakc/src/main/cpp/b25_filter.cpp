#include <unistd.h>
#include <android/log.h>

#include "arib_std_b25.h"
#include "b_cas_card.h"
#include "ccid_reader.h"

#define LOG_TAG "b25-filter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int passthrough_loop() {
    uint8_t copy[32 * 1024];
    while (true) {
        ssize_t n = read(STDIN_FILENO, copy, sizeof(copy));
        if (n <= 0) break;
        if (write(STDOUT_FILENO, copy, (size_t)n) < 0) break;
    }
    return 1;
}

extern "C" int b25_stdio_filter(int reader_fd) {
    if (ccid_open(reader_fd) != 0) {
        LOGE("ccid_open failed, passing TS through");
        return passthrough_loop();
    }

    B_CAS_CARD *bcas = create_b_cas_card();
    if (bcas == nullptr || bcas->init(bcas) != 0) {
        LOGE("B-CAS init failed, passing TS through");
        if (bcas) bcas->release(bcas);
        ccid_close();
        return passthrough_loop();
    }

    ARIB_STD_B25 *b25 = create_arib_std_b25();
    if (b25 == nullptr) {
        bcas->release(bcas);
        ccid_close();
        return passthrough_loop();
    }
    b25->set_multi2_round(b25, 4);
    b25->set_strip(b25, 0);
    b25->set_emm_proc(b25, 1);
    if (b25->set_b_cas_card(b25, bcas) != 0) {
        LOGE("set_b_cas_card failed");
        b25->release(b25);
        bcas->release(bcas);
        ccid_close();
        return passthrough_loop();
    }
    LOGI("B25 decoder ready");

    uint8_t buf[64 * 1024];
    while (true) {
        ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
        if (n < 0) break;
        if (n == 0) break;
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
