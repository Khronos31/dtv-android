#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "../ccid_reader.c"

static char captured_log[4096];
static size_t captured_log_length;

static const uint8_t synthetic_payload[] = {0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6};
static const int synthetic_sequence = 7;

int __android_log_print(int priority, const char* tag, const char* format, ...) {
    char message[512];
    va_list arguments;
    (void)priority;
    va_start(arguments, format);
    const int message_length = vsnprintf(message, sizeof(message), format, arguments);
    va_end(arguments);
    if (message_length < 0) return message_length;

    const int written = snprintf(captured_log + captured_log_length,
                                 sizeof(captured_log) - captured_log_length,
                                 "%s: %s\n", tag, message);
    if (written > 0) {
        const size_t remaining = sizeof(captured_log) - captured_log_length;
        captured_log_length += (size_t)written < remaining ? (size_t)written : remaining - 1;
    }
    return written;
}

int ioctl(int fd, unsigned long request, ...) {
    va_list arguments;
    (void)fd;
    if (request != USBDEVFS_BULK) return -1;

    va_start(arguments, request);
    struct usbdevfs_bulktransfer* transfer = va_arg(arguments, struct usbdevfs_bulktransfer*);
    va_end(arguments);

    if (transfer->ep == CCID_OUT) return (int)transfer->len;
    if (transfer->ep != CCID_IN || transfer->len < 10 + sizeof(synthetic_payload)) return -1;

    uint8_t* response = (uint8_t*)transfer->data;
    memset(response, 0, 10 + sizeof(synthetic_payload));
    response[0] = RDR_TO_PC_DATA_BLOCK;
    put_le32(response + 1, (uint32_t)sizeof(synthetic_payload));
    response[5] = 0;
    response[6] = (uint8_t)synthetic_sequence;
    response[7] = 0;
    response[8] = 0;
    response[9] = 0;
    memcpy(response + 10, synthetic_payload, sizeof(synthetic_payload));
    return (int)(10 + sizeof(synthetic_payload));
}

static int log_contains_payload_hex(void) {
    char payload_hex[sizeof(synthetic_payload) * 3];
    size_t offset = 0;
    for (size_t index = 0; index < sizeof(synthetic_payload); ++index) {
        const int written = snprintf(payload_hex + offset, sizeof(payload_hex) - offset,
                                     index == 0 ? "%02x" : " %02x", synthetic_payload[index]);
        if (written < 0 || (size_t)written >= sizeof(payload_hex) - offset) return 1;
        offset += (size_t)written;
    }
    return strstr(captured_log, payload_hex) != NULL;
}

static void clear_log(void) {
    captured_log[0] = '\0';
    captured_log_length = 0;
}

int main(void) {
    if (send_ccid(PC_TO_RDR_XFR_BLOCK, synthetic_payload,
                  (uint32_t)sizeof(synthetic_payload), 0, 0, 0) < 0) {
        fputs("failed: synthetic CCID request\n", stderr);
        return 1;
    }
    if (strstr(captured_log, "OUT type=0x6f len=6") == NULL || log_contains_payload_hex()) {
        fputs("failed: CCID transmit log exposed payload bytes or omitted metadata\n", stderr);
        return 1;
    }

    clear_log();
    uint8_t response[sizeof(synthetic_payload)] = {0};
    uint8_t chain = 0xff;
    const int response_length = recv_ccid(RDR_TO_PC_DATA_BLOCK, synthetic_sequence,
                                          response, (int)sizeof(response), &chain);
    if (response_length != (int)sizeof(synthetic_payload) ||
        memcmp(response, synthetic_payload, sizeof(synthetic_payload)) != 0 || chain != 0) {
        fputs("failed: synthetic CCID response fixture\n", stderr);
        return 1;
    }
    if (strstr(captured_log, "IN  type=0x80 len=6 slot=0 seq=7") == NULL ||
        log_contains_payload_hex()) {
        fputs("failed: CCID receive log exposed payload bytes or omitted metadata\n", stderr);
        return 1;
    }

    puts("CCID log redaction tests: PASS");
    return 0;
}
