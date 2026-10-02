#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "../ccid_reader.c"

static char captured_log[4096];
static size_t captured_log_length;

static const uint8_t synthetic_payload[] = {0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6};
static const uint8_t synthetic_atr[] = {0x3b, 0xa4, 0x13, 0x00, 0xe5, 0xf6};
static const uint8_t synthetic_descriptor_secret[] = {0xde, 0xad, 0xbe, 0xef, 0xfa};
static const int synthetic_sequence = 7;
static uint8_t last_request_type;
static uint8_t last_request_sequence;

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

    if (transfer->ep == CCID_OUT) {
        const uint8_t* command = (const uint8_t*)transfer->data;
        if (transfer->len < 10) return -1;
        last_request_type = command[0];
        last_request_sequence = command[6];
        return (int)transfer->len;
    }
    if (transfer->ep != CCID_IN || transfer->len < 10 + sizeof(synthetic_payload)) return -1;

    uint8_t* response = (uint8_t*)transfer->data;
    const uint8_t* body = synthetic_payload;
    uint32_t body_length = (uint32_t)sizeof(synthetic_payload);
    uint8_t response_type = RDR_TO_PC_DATA_BLOCK;
    if (last_request_type == PC_TO_RDR_ICC_POWER_OFF) {
        response_type = RDR_TO_PC_SLOT_STATUS;
        body_length = 0;
    } else if (last_request_type == PC_TO_RDR_ICC_POWER_ON) {
        response_type = RDR_TO_PC_DATA_BLOCK;
        body = synthetic_atr;
        body_length = (uint32_t)sizeof(synthetic_atr);
    } else if (last_request_type == PC_TO_RDR_GET_PARAMETERS) {
        response_type = RDR_TO_PC_PARAMETERS;
        body_length = 0;
    }
    memset(response, 0, 10 + sizeof(synthetic_payload));
    response[0] = response_type;
    put_le32(response + 1, body_length);
    response[5] = 0;
    response[6] = last_request_sequence;
    response[7] = 0;
    response[8] = 0;
    response[9] = 0;
    if (body_length > 0) memcpy(response + 10, body, body_length);
    return (int)(10 + body_length);
}

ssize_t pread(int fd, void* buffer, size_t count, off_t offset) {
    uint8_t descriptor[54] = {0};
    (void)fd;
    if (offset != 0 || count < sizeof(descriptor)) return -1;
    descriptor[0] = sizeof(descriptor);
    descriptor[1] = 0x21;
    descriptor[6] = 0x03;
    descriptor[28] = 32;
    descriptor[40] = 0;
    descriptor[41] = 0;
    descriptor[42] = 0x02;
    descriptor[44] = 0x0f;
    memcpy(descriptor + 48, synthetic_descriptor_secret, sizeof(synthetic_descriptor_secret));
    memcpy(buffer, descriptor, sizeof(descriptor));
    return (ssize_t)sizeof(descriptor);
}

static int log_contains_bytes_hex(const uint8_t* bytes, size_t length) {
    char spaced_hex[sizeof(synthetic_atr) * 3];
    char compact_hex[sizeof(synthetic_atr) * 2 + 1];
    size_t offset = 0;
    for (size_t index = 0; index < length; ++index) {
        const int written = snprintf(spaced_hex + offset, sizeof(spaced_hex) - offset,
                                     index == 0 ? "%02x" : " %02x", bytes[index]);
        if (written < 0 || (size_t)written >= sizeof(spaced_hex) - offset) return 1;
        offset += (size_t)written;
        (void)snprintf(compact_hex + index * 2, sizeof(compact_hex) - index * 2,
                       "%02x", bytes[index]);
    }
    return strstr(captured_log, spaced_hex) != NULL || strstr(captured_log, compact_hex) != NULL;
}

static int metadata_log_count(void) {
    int count = 0;
    const char* position = captured_log;
    while ((position = strstr(position, "OUT type=")) != NULL) {
        ++count;
        ++position;
    }
    position = captured_log;
    while ((position = strstr(position, "IN  type=")) != NULL) {
        ++count;
        ++position;
    }
    return count;
}

static void clear_log(void) {
    captured_log[0] = '\0';
    captured_log_length = 0;
}

int main(void) {
    g_seq = (uint8_t)synthetic_sequence;
    if (send_ccid(PC_TO_RDR_XFR_BLOCK, synthetic_payload,
                  (uint32_t)sizeof(synthetic_payload), 0, 0, 0) < 0) {
        fputs("failed: synthetic CCID request\n", stderr);
        return 1;
    }
    if (strstr(captured_log, "OUT type=0x6f len=6") == NULL ||
        log_contains_bytes_hex(synthetic_payload, sizeof(synthetic_payload))) {
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
        log_contains_bytes_hex(synthetic_payload, sizeof(synthetic_payload))) {
        fputs("failed: CCID receive log exposed payload bytes or omitted metadata\n", stderr);
        return 1;
    }

    clear_log();
    if (ccid_open(17) != 0 || strstr(captured_log, "CCID desc dwProtocols=") == NULL ||
        log_contains_bytes_hex(synthetic_descriptor_secret, sizeof(synthetic_descriptor_secret))) {
        fputs("failed: synthetic CCID descriptor leaked bytes or omitted metadata\n", stderr);
        return 1;
    }

    clear_log();
    if (ccid_power_on() != 0 || strstr(captured_log, "ATR 6 bytes") == NULL ||
        log_contains_bytes_hex(synthetic_atr, sizeof(synthetic_atr))) {
        fputs("failed: synthetic ATR leaked bytes or omitted its length diagnostic\n", stderr);
        return 1;
    }

    (void)ccid_open(17);
    clear_log();
    for (int index = 0; index < 40; ++index) {
        const int sequence = send_ccid(PC_TO_RDR_XFR_BLOCK, synthetic_payload,
                                       (uint32_t)sizeof(synthetic_payload), 0, 0, 0);
        uint8_t response[sizeof(synthetic_payload)] = {0};
        uint8_t chain = 0;
        if (sequence < 0 || recv_ccid(RDR_TO_PC_DATA_BLOCK, sequence, response,
                                      (int)sizeof(response), &chain) != (int)sizeof(response)) {
            fputs("failed: repeated synthetic CCID transfer\n", stderr);
            return 1;
        }
    }
    if (metadata_log_count() != 32 ||
        strstr(captured_log, "CCID transfer metadata log limit reached") == NULL ||
        log_contains_bytes_hex(synthetic_payload, sizeof(synthetic_payload))) {
        fputs("failed: CCID transfer metadata logging is not bounded or leaked bytes\n", stderr);
        return 1;
    }

    puts("CCID log redaction and bounded-diagnostics tests: PASS");
    return 0;
}
