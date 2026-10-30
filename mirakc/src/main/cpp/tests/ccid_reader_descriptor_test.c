#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "../ccid_reader.c"

/*
 * Verifies that the CCID reader derives the interface, bulk endpoints and
 * slot domain from the USB descriptor blob instead of assuming fixed
 * endpoints, that unsupported layouts (multiple configurations, nonzero
 * alternate settings, missing CCID class descriptor) and malformed blobs
 * are rejected explicitly, and that replies for the wrong CCID slot are
 * discarded with a bounded retry before failing closed.
 * Also exercises the T=1 R-block retransmission through the real CCID
 * transport (ccid_transmit -> t1_state_machine -> USBDEVFS_BULK stubs).
 */

static char captured_log[8192];
static size_t captured_log_length;

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

static void clear_log(void) {
    captured_log[0] = '\0';
    captured_log_length = 0;
}

/* ------------------------------------------------------------------ */
/* Synthetic descriptor blobs                                          */
/* ------------------------------------------------------------------ */

typedef struct {
    uint8_t bytes[256];
    size_t length;
    size_t config_offset;
    int has_config;
} blob_builder;

static void blob_device_configs(blob_builder* builder, uint8_t configurations) {
    uint8_t descriptor[18] = {0};
    descriptor[0] = sizeof(descriptor);
    descriptor[1] = 0x01;
    descriptor[8] = 0xff;  /* vendor class at device level; interfaces decide */
    descriptor[9] = 0xff;
    descriptor[10] = 0xff;
    descriptor[17] = configurations;
    memcpy(builder->bytes + builder->length, descriptor, sizeof(descriptor));
    builder->length += sizeof(descriptor);
}

static void blob_device(blob_builder* builder) {
    blob_device_configs(builder, 1); /* one configuration */
}

static void blob_config(blob_builder* builder) {
    static const uint8_t config[] = {9, 0x02, 0, 0, 1, 0, 0, 0x80, 0x32};
    if (!builder->has_config) builder->config_offset = builder->length;
    builder->has_config = 1;
    memcpy(builder->bytes + builder->length, config, sizeof(config));
    builder->length += sizeof(config);
}

static void blob_interface_full(blob_builder* builder, uint8_t number, uint8_t alt,
                                uint8_t klass, uint8_t endpoints) {
    uint8_t descriptor[] = {9, 0x04, number, alt, endpoints, klass, 0, 0, 0};
    memcpy(builder->bytes + builder->length, descriptor, sizeof(descriptor));
    builder->length += sizeof(descriptor);
}

static void blob_interface(blob_builder* builder, uint8_t number, uint8_t klass,
                           uint8_t endpoints) {
    blob_interface_full(builder, number, 0, klass, endpoints);
}

static void blob_ccid(blob_builder* builder, uint8_t max_slot, uint32_t features) {
    uint8_t descriptor[54] = {0};
    descriptor[0] = sizeof(descriptor);
    descriptor[1] = 0x21;
    descriptor[4] = max_slot;
    descriptor[6] = 0x03; /* dwProtocols = T=0 | T=1 */
    descriptor[28] = 32;  /* dwMaxIFSD */
    descriptor[40] = (uint8_t)features;
    descriptor[41] = (uint8_t)(features >> 8);
    descriptor[42] = (uint8_t)(features >> 16);
    descriptor[43] = (uint8_t)(features >> 24);
    descriptor[44] = 0x0f; /* dwMaxCCIDMessageLength low byte */
    memcpy(builder->bytes + builder->length, descriptor, sizeof(descriptor));
    builder->length += sizeof(descriptor);
}

static void blob_endpoint(blob_builder* builder, uint8_t address, uint8_t attributes) {
    uint8_t descriptor[] = {7, 0x05, address, attributes, 64, 0, 0};
    memcpy(builder->bytes + builder->length, descriptor, sizeof(descriptor));
    builder->length += sizeof(descriptor);
}

static void blob_raw(blob_builder* builder, const uint8_t* bytes, size_t length) {
    memcpy(builder->bytes + builder->length, bytes, length);
    builder->length += length;
}

/* Stamps a consistent wTotalLength over the single configuration tree. */
static void blob_finish(blob_builder* builder) {
    const uint32_t total = (uint32_t)(builder->length - builder->config_offset);
    builder->bytes[builder->config_offset + 2] = (uint8_t)total;
    builder->bytes[builder->config_offset + 3] = (uint8_t)(total >> 8);
}

/* Stamps a deliberately wrong wTotalLength (truncated-read simulation). */
static void blob_finish_with_total(blob_builder* builder, uint32_t total) {
    builder->bytes[builder->config_offset + 2] = (uint8_t)total;
    builder->bytes[builder->config_offset + 3] = (uint8_t)(total >> 8);
}

#define FEATURES_SCR33XX_TPDU 0x000100baUL
#define FEATURES_SHORT_APDU 0x00020000UL

static const uint8_t* fixture_blob;
static size_t fixture_blob_length;
static int fixture_pread_fail;

/* ------------------------------------------------------------------ */
/* USB stubs                                                           */
/* ------------------------------------------------------------------ */

typedef struct {
    int ep;
    uint8_t type;
    uint8_t seq;
    uint8_t payload[300];
    int payload_length;
} recorded_transfer;

static recorded_transfer out_transfers[16];
static int out_transfer_count;
static int in_transfer_count;
static int last_in_ep = -1;

/* T=1 (or raw APDU) payloads the reader returns for consecutive XFR_BLOCKs. */
static uint8_t scripted_payloads[16][300];
static int scripted_payload_lengths[16];
static uint8_t scripted_slots[16];
static int scripted_payload_count;
static int scripted_payload_index;

static void reset_usb_stub(void) {
    out_transfer_count = 0;
    in_transfer_count = 0;
    last_in_ep = -1;
    scripted_payload_count = 0;
    scripted_payload_index = 0;
}

static void script_response_slot(const uint8_t* payload, int payload_length, uint8_t slot) {
    memcpy(scripted_payloads[scripted_payload_count], payload, (size_t)payload_length);
    scripted_payload_lengths[scripted_payload_count] = payload_length;
    scripted_slots[scripted_payload_count] = slot;
    ++scripted_payload_count;
}

static void script_response(const uint8_t* payload, int payload_length) {
    script_response_slot(payload, payload_length, 0);
}

ssize_t pread(int fd, void* buffer, size_t count, off_t offset) {
    (void)fd;
    if (fixture_pread_fail || offset != 0 || fixture_blob == NULL ||
        count < fixture_blob_length) {
        return -1;
    }
    memcpy(buffer, fixture_blob, fixture_blob_length);
    return (ssize_t)fixture_blob_length;
}

int ioctl(int fd, unsigned long request, ...) {
    va_list arguments;
    (void)fd;
    if (request != USBDEVFS_BULK) return -1;

    va_start(arguments, request);
    struct usbdevfs_bulktransfer* transfer = va_arg(arguments, struct usbdevfs_bulktransfer*);
    va_end(arguments);

    if (transfer->ep == (unsigned int)g_ep_out) {
        const uint8_t* command = (const uint8_t*)transfer->data;
        if (transfer->len < 10 || out_transfer_count >= 16) return -1;
        recorded_transfer* record = &out_transfers[out_transfer_count++];
        record->ep = (int)transfer->ep;
        record->type = command[0];
        record->seq = command[6];
        record->payload_length = (int)transfer->len - 10;
        memcpy(record->payload, command + 10, (size_t)record->payload_length);
        return (int)transfer->len;
    }
    if (transfer->ep != (unsigned int)g_ep_in) return -1;
    ++in_transfer_count;
    last_in_ep = (int)transfer->ep;
    if (scripted_payload_index >= scripted_payload_count) return -1;

    const int payload_length = scripted_payload_lengths[scripted_payload_index];
    const uint8_t slot = scripted_slots[scripted_payload_index];
    ++scripted_payload_index;
    uint8_t* response = (uint8_t*)transfer->data;
    memset(response, 0, 10);
    response[0] = RDR_TO_PC_DATA_BLOCK;
    put_le32(response + 1, (uint32_t)payload_length);
    response[5] = slot;
    response[6] = out_transfers[out_transfer_count - 1].seq;
    if (payload_length > 0) {
        memcpy(response + 10, scripted_payloads[scripted_payload_index - 1],
               (size_t)payload_length);
    }
    return 10 + payload_length;
}

/* ------------------------------------------------------------------ */
/* Assertions                                                          */
/* ------------------------------------------------------------------ */

static int failures;

static void check(int condition, const char* name) {
    if (!condition) {
        fprintf(stderr, "failed: %s\n", name);
        ++failures;
    }
}

static int open_with_fixture(const blob_builder* builder) {
    fixture_blob = builder->bytes;
    fixture_blob_length = builder->length;
    fixture_pread_fail = 0;
    return ccid_open(17);
}

static void make_scr33xx(blob_builder* builder) {
    blob_device(builder);
    blob_config(builder);
    blob_interface(builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(builder);
}

/* ------------------------------------------------------------------ */
/* Cases                                                               */
/* ------------------------------------------------------------------ */

/* SCR33xx v2.0 layout: one config, interface 0 alt 0, TPDU, OUT=0x01 IN=0x82. */
static void test_default_tpdu_layout(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    make_scr33xx(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) == 0, "default layout opens");
    check(g_ep_out == 0x01 && g_ep_in == 0x82, "default endpoints derived");
    check(g_interface == 0 && g_slot == 0, "default interface and slot");
    check(g_tpdu == 1, "TPDU exchange level detected");
}

/*
 * The T=1 retransmission path at the real transport level: the card rejects
 * the first I-block with an R-block error and accepts the byte-identical
 * retransmission.
 */
static void test_ccid_level_r_block_retransmission(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    make_scr33xx(&builder);
    check(open_with_fixture(&builder) == 0, "retransmission fixture opens");

    reset_usb_stub();
    static const uint8_t r_block_error[] = {0x00, 0x81, 0x00, 0x81};
    static const uint8_t i_block_response[] = {0x00, 0x00, 0x02, 0x90, 0x00, 0x92};
    script_response(r_block_error, (int)sizeof(r_block_error));
    script_response(i_block_response, (int)sizeof(i_block_response));

    static const uint8_t apdu[] = {0x90, 0x34, 0x00, 0x00, 0x01, 0x00};
    uint8_t response[64] = {0};
    const int length =
        ccid_transmit(apdu, (int)sizeof(apdu), response, (int)sizeof(response));
    check(length == 2 && response[0] == 0x90 && response[1] == 0x00,
          "response recovered after R-block retransmission");
    check(out_transfer_count == 2, "exactly one retransmission");
    check(out_transfer_count == 2 &&
              memcmp(out_transfers[0].payload, out_transfers[1].payload,
                     (size_t)out_transfers[0].payload_length) == 0 &&
              out_transfers[0].payload_length == out_transfers[1].payload_length,
          "retransmitted T=1 block is byte-identical");
    check(out_transfer_count == 2 && out_transfers[0].seq == 0 && out_transfers[1].seq == 1,
          "retransmission uses a fresh CCID bSeq");
}

/* A reader with the CCID interface and bulk endpoints on non-default addresses. */
static void test_nondefault_layout(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, 0xff, 1);           /* vendor interface, ignored */
    blob_endpoint(&builder, 0x05, USB_ENDPOINT_XFER_BULK);
    blob_interface(&builder, 1, USB_CLASS_CCID, 3); /* CCID on interface 1 */
    blob_ccid(&builder, 1, FEATURES_SHORT_APDU);
    blob_endpoint(&builder, 0x02, USB_ENDPOINT_XFER_BULK); /* OUT */
    blob_endpoint(&builder, 0x84, USB_ENDPOINT_XFER_BULK); /* bulk IN */
    blob_endpoint(&builder, 0x85, 0x03);                   /* interrupt IN */
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) == 0, "nondefault layout opens");
    check(g_ep_out == 0x02 && g_ep_in == 0x84, "nondefault bulk endpoints derived");
    check(g_interface == 1 && g_slot == 0, "nondefault interface and slot");
    check(g_tpdu == 0, "APDU exchange level detected");

    static const uint8_t apdu_response[] = {0x6f, 0x00};
    script_response(apdu_response, (int)sizeof(apdu_response));
    static const uint8_t apdu[] = {0x90, 0x32, 0x00, 0x00, 0x00};
    uint8_t response[16] = {0};
    const int length =
        ccid_transmit(apdu, (int)sizeof(apdu), response, (int)sizeof(response));
    check(length == 2 && response[0] == 0x6f && response[1] == 0x00,
          "APDU exchange succeeds on nondefault layout");
    check(out_transfer_count == 1 && out_transfers[0].ep == 0x02,
          "OUT transfer used the derived endpoint");
    check(last_in_ep == 0x84, "IN transfer used the derived endpoint");
}

/* Multiple usable CCID interfaces: the first one wins, with a diagnostic. */
static void test_ambiguous_interfaces_pick_first(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SHORT_APDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_interface(&builder, 1, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SHORT_APDU);
    blob_endpoint(&builder, 0x03, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x86, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) == 0, "ambiguous layout opens");
    check(g_interface == 0 && g_ep_out == 0x01 && g_ep_in == 0x82,
          "first usable interface wins");
    check(strstr(captured_log, "usable CCID interfaces") != NULL,
          "ambiguity is logged");
}

/* A CCID interface without a bulk IN endpoint is rejected, not misused. */
static void test_missing_bulk_in_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 1);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "missing bulk IN rejected");
}

/* No CCID interface at all: explicit rejection instead of fixed endpoints. */
static void test_no_ccid_interface_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, 0xff, 1);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "non-CCID device rejected");
    check(strstr(captured_log, "no usable CCID interface") != NULL,
          "rejection is logged");
}

/* The interface advertises endpoints the blob never delivers: rejected. */
static void test_missing_endpoints_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "endpoint-less interface rejected");
}

/* An unreadable descriptor is an explicit open failure. */
static void test_pread_failure_rejected(void) {
    reset_usb_stub();
    clear_log();
    fixture_blob = NULL;
    fixture_blob_length = 0;
    fixture_pread_fail = 1;
    check(ccid_open(17) != 0, "descriptor read failure rejected");
}

/* A garbage tail after a valid tree must not be adopted silently. */
static void test_malformed_tail_after_valid_pair_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    make_scr33xx(&builder);
    /* Append a truncated descriptor start: declares 9 bytes, delivers 3. */
    static const uint8_t tail[] = {9, 0x04, 0x01};
    blob_raw(&builder, tail, sizeof(tail));
    blob_finish(&builder); /* consistent length, unparseable content */

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "malformed tail rejected");
}

/* wTotalLength beyond the blob models a truncated 1024-byte read: rejected. */
static void test_inconsistent_total_length_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    make_scr33xx(&builder);
    blob_finish_with_total(&builder, (uint32_t)builder.length + 32);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "inconsistent wTotalLength rejected");
}

/* An undersized interface descriptor must not re-own later endpoints. */
static void test_undersized_interface_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    static const uint8_t undersized[] = {4, 0x04, 0x01, 0x00};
    blob_raw(&builder, undersized, sizeof(undersized));
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "undersized interface rejected");
}

/* An undersized CCID class descriptor is malformed, not skipped. */
static void test_undersized_class_descriptor_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    static const uint8_t short_ccid[20] = {20, 0x21};
    blob_raw(&builder, short_ccid, sizeof(short_ccid));
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "undersized class descriptor rejected");
}

/* A CCID interface without its class descriptor is not assumed TPDU. */
static void test_missing_class_descriptor_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "missing class descriptor rejected");
}

/* Bulk endpoints living only on alternate 1 cannot be used by this host. */
static void test_nonzero_alternate_endpoints_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface_full(&builder, 0, 0, USB_CLASS_CCID, 0);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_interface_full(&builder, 0, 1, USB_CLASS_CCID, 2);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "nonzero-alternate endpoints rejected");
}

/* Multiple configurations are an unsupported layout, not a guessing game. */
static void test_multiple_configurations_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    make_scr33xx(&builder);
    blob_config(&builder);
    blob_interface(&builder, 1, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x04, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x83, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "multiple configurations rejected");
    check(strstr(captured_log, "multiple USB configurations") != NULL,
          "multi-config rejection is logged");
}

/* Two bulk OUT endpoints on the chosen interface are ambiguous: rejected. */
static void test_duplicate_bulk_out_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 3);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x04, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "duplicate bulk OUT rejected");
}

/* An endpoint with no owning interface is invalid ownership: rejected. */
static void test_orphaned_endpoint_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_config(&builder);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x02, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "orphaned endpoint rejected");
    check(strstr(captured_log, "orphaned endpoint") != NULL,
          "orphaned endpoint is logged");
}

/*
 * Interfaces and endpoints that appear before the configuration descriptor
 * belong to no configuration; adopting them would let an empty trailing
 * configuration validate endpoints it does not own.
 */
static void test_interface_tree_before_config_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_config(&builder); /* empty configuration at the tail */
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "interface tree before configuration rejected");
}

/* The device advertises two configurations; the second may lie past the read. */
static void test_device_with_two_configurations_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device_configs(&builder, 2);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0,
          "device advertising two configurations rejected");
}

/* No device descriptor prefix at all: the blob is not a USBFS read. */
static void test_missing_device_prefix_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "missing device prefix rejected");
}

/* A second device descriptor inside the blob is a malformed prefix. */
static void test_duplicate_device_prefix_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_device(&builder);
    blob_device(&builder);
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "duplicate device prefix rejected");
}

/* The device descriptor must be full length. */
static void test_undersized_device_prefix_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    static const uint8_t short_device[8] = {8, 0x01, 0x00, 0x02, 0xff, 0xff, 0xff, 0x01};
    blob_raw(&builder, short_device, sizeof(short_device));
    blob_config(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "undersized device prefix rejected");
}

/* A configuration before the device descriptor violates the blob order. */
static void test_config_before_device_rejected(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    blob_config(&builder);
    blob_device(&builder);
    blob_interface(&builder, 0, USB_CLASS_CCID, 2);
    blob_ccid(&builder, 0, FEATURES_SCR33XX_TPDU);
    blob_endpoint(&builder, 0x01, USB_ENDPOINT_XFER_BULK);
    blob_endpoint(&builder, 0x82, USB_ENDPOINT_XFER_BULK);
    blob_finish(&builder);

    reset_usb_stub();
    clear_log();
    check(open_with_fixture(&builder) != 0, "configuration before device rejected");
}

/* Replies for another slot are discarded; the matching reply still arrives. */
static void test_wrong_slot_reply_is_retried(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    make_scr33xx(&builder);
    check(open_with_fixture(&builder) == 0, "slot retry fixture opens");

    reset_usb_stub();
    static const uint8_t i_block_response[] = {0x00, 0x00, 0x02, 0x90, 0x00, 0x92};
    script_response_slot(i_block_response, (int)sizeof(i_block_response), 1);
    script_response_slot(i_block_response, (int)sizeof(i_block_response), 0);

    static const uint8_t apdu[] = {0x90, 0x34, 0x00, 0x00, 0x01, 0x00};
    uint8_t response[64] = {0};
    const int length =
        ccid_transmit(apdu, (int)sizeof(apdu), response, (int)sizeof(response));
    check(length == 2 && response[0] == 0x90 && response[1] == 0x00,
          "reply accepted after discarding the wrong-slot reply");
    check(in_transfer_count == 2, "exactly one wrong-slot retry");
    check(out_transfer_count == 1, "wrong slot does not retrigger the OUT transfer");
}

/* Every reply for the wrong slot: bounded retries, then fail closed. */
static void test_all_wrong_slot_replies_fail_closed(void) {
    blob_builder builder = {{0}, 0, 0, 0};
    make_scr33xx(&builder);
    check(open_with_fixture(&builder) == 0, "slot failure fixture opens");

    reset_usb_stub();
    static const uint8_t i_block_response[] = {0x00, 0x00, 0x02, 0x90, 0x00, 0x92};
    for (int index = 0; index < 12; ++index) {
        script_response_slot(i_block_response, (int)sizeof(i_block_response), 2);
    }

    static const uint8_t apdu[] = {0x90, 0x34, 0x00, 0x00, 0x01, 0x00};
    uint8_t response[64] = {0};
    const int length =
        ccid_transmit(apdu, (int)sizeof(apdu), response, (int)sizeof(response));
    check(length < 0, "persistent wrong-slot replies fail closed");
    check(in_transfer_count == 8, "wrong-slot retries are bounded");
}

int main(void) {
    test_default_tpdu_layout();
    test_ccid_level_r_block_retransmission();
    test_nondefault_layout();
    test_ambiguous_interfaces_pick_first();
    test_missing_bulk_in_rejected();
    test_no_ccid_interface_rejected();
    test_missing_endpoints_rejected();
    test_pread_failure_rejected();
    test_malformed_tail_after_valid_pair_rejected();
    test_inconsistent_total_length_rejected();
    test_undersized_interface_rejected();
    test_undersized_class_descriptor_rejected();
    test_missing_class_descriptor_rejected();
    test_nonzero_alternate_endpoints_rejected();
    test_multiple_configurations_rejected();
    test_duplicate_bulk_out_rejected();
    test_orphaned_endpoint_rejected();
    test_interface_tree_before_config_rejected();
    test_device_with_two_configurations_rejected();
    test_missing_device_prefix_rejected();
    test_duplicate_device_prefix_rejected();
    test_undersized_device_prefix_rejected();
    test_config_before_device_rejected();
    test_wrong_slot_reply_is_retried();
    test_all_wrong_slot_replies_fail_closed();
    if (failures != 0) {
        fprintf(stderr, "CCID descriptor selection tests: %d failure(s)\n", failures);
        return 1;
    }
    puts("CCID descriptor selection tests: PASS");
    return 0;
}
