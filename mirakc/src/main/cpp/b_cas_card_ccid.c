#include "b_cas_card.h"
#include "b_cas_card_error_code.h"
#include "ccid_reader.h"

#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "b25-bcas"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define B_CAS_BUFFER_MAX (4 * 1024)

typedef struct {
    uint8_t *sbuf;
    uint8_t *rbuf;
    B_CAS_INIT_STATUS stat;
    B_CAS_ID id;
    int32_t id_max;
    int initialized;
} B_CAS_CARD_PRIVATE_DATA;

static const uint8_t INITIAL_SETTING_CONDITIONS_CMD[] = {0x90, 0x30, 0x00, 0x00, 0x00};
static const uint8_t CARD_ID_INFORMATION_ACQUIRE_CMD[] = {0x90, 0x32, 0x00, 0x00, 0x00};
static const uint8_t ECM_RECEIVE_CMD_HEADER[] = {0x90, 0x34, 0x00, 0x00};
static const uint8_t EMM_RECEIVE_CMD_HEADER[] = {0x90, 0x36, 0x00, 0x00};

static void release_b_cas_card(void *bcas);
static int init_b_cas_card(void *bcas);
static int get_init_status_b_cas_card(void *bcas, B_CAS_INIT_STATUS *stat);
static int get_id_b_cas_card(void *bcas, B_CAS_ID *dst);
static int get_pwr_on_ctrl_b_cas_card(void *bcas, B_CAS_PWR_ON_CTRL_INFO *dst);
static int proc_ecm_b_cas_card(void *bcas, B_CAS_ECM_RESULT *dst, uint8_t *src, int len);
static int proc_emm_b_cas_card(void *bcas, uint8_t *src, int len);

static B_CAS_CARD_PRIVATE_DATA *private_data(void *bcas) {
    B_CAS_CARD *p = (B_CAS_CARD *)bcas;
    if (p == NULL) return NULL;
    return (B_CAS_CARD_PRIVATE_DATA *)p->private_data;
}

static int32_t load_be_uint16(uint8_t *p) { return (p[0] << 8) | p[1]; }

static int64_t load_be_uint48(uint8_t *p) {
    int64_t r = p[0];
    for (int i = 1; i < 6; i++) {
        r <<= 8;
        r |= p[i];
    }
    return r;
}

static int setup_ecm_receive_command(uint8_t *dst, uint8_t *src, int len) {
    int r = (int)sizeof(ECM_RECEIVE_CMD_HEADER);
    memcpy(dst, ECM_RECEIVE_CMD_HEADER, (size_t)r);
    dst[r++] = (uint8_t)(len & 0xff);
    memcpy(dst + r, src, (size_t)len);
    r += len;
    dst[r++] = 0;
    return r;
}

static int setup_emm_receive_command(uint8_t *dst, uint8_t *src, int len) {
    int r = (int)sizeof(EMM_RECEIVE_CMD_HEADER);
    memcpy(dst, EMM_RECEIVE_CMD_HEADER, (size_t)r);
    dst[r++] = (uint8_t)(len & 0xff);
    memcpy(dst + r, src, (size_t)len);
    r += len;
    dst[r++] = 0;
    return r;
}

ARIB25_API_EXPORT B_CAS_CARD *create_b_cas_card() {
    size_t n = sizeof(B_CAS_CARD) + sizeof(B_CAS_CARD_PRIVATE_DATA);
    B_CAS_CARD_PRIVATE_DATA *prv = (B_CAS_CARD_PRIVATE_DATA *)calloc(1, n);
    if (prv == NULL) return NULL;
    B_CAS_CARD *r = (B_CAS_CARD *)(prv + 1);
    r->private_data = prv;
    r->release = release_b_cas_card;
    r->init = init_b_cas_card;
    r->get_init_status = get_init_status_b_cas_card;
    r->get_id = get_id_b_cas_card;
    r->get_pwr_on_ctrl = get_pwr_on_ctrl_b_cas_card;
    r->proc_ecm = proc_ecm_b_cas_card;
    r->proc_emm = proc_emm_b_cas_card;
    return r;
}

static void release_b_cas_card(void *bcas) {
    B_CAS_CARD_PRIVATE_DATA *prv = private_data(bcas);
    if (prv == NULL) return;
    free(prv->sbuf);
    free(prv->id.data);
    ccid_close();
    free(prv);
}

static int init_b_cas_card(void *bcas) {
    B_CAS_CARD_PRIVATE_DATA *prv = private_data(bcas);
    if (prv == NULL) return B_CAS_CARD_ERROR_INVALID_PARAMETER;
    if (ccid_power_on() != 0) return B_CAS_CARD_ERROR_ALL_READERS_CONNECTION_FAILED;

    prv->sbuf = (uint8_t *)malloc(2 * B_CAS_BUFFER_MAX);
    if (prv->sbuf == NULL) return B_CAS_CARD_ERROR_NO_ENOUGH_MEMORY;
    prv->rbuf = prv->sbuf + B_CAS_BUFFER_MAX;
    prv->id_max = 16;
    prv->id.data = (int64_t *)calloc((size_t)prv->id_max, sizeof(int64_t));
    if (prv->id.data == NULL) return B_CAS_CARD_ERROR_NO_ENOUGH_MEMORY;

    int n = ccid_transmit(INITIAL_SETTING_CONDITIONS_CMD, (int)sizeof(INITIAL_SETTING_CONDITIONS_CMD),
                          prv->rbuf, B_CAS_BUFFER_MAX);
    if (n < 57) {
        LOGE("initial setting failed n=%d", n);
        return B_CAS_CARD_ERROR_TRANSMIT_FAILED;
    }
    if (load_be_uint16(prv->rbuf + 4) != 0x2100) {
        LOGE("initial setting code=0x%04x", load_be_uint16(prv->rbuf + 4));
        return B_CAS_CARD_ERROR_TRANSMIT_FAILED;
    }
    memcpy(prv->stat.system_key, prv->rbuf + 16, 32);
    memcpy(prv->stat.init_cbc, prv->rbuf + 48, 8);
    prv->stat.bcas_card_id = load_be_uint48(prv->rbuf + 8);
    prv->stat.card_status = load_be_uint16(prv->rbuf + 2);
    prv->stat.ca_system_id = load_be_uint16(prv->rbuf + 6);
    prv->initialized = 1;
    LOGI("B-CAS ready id=%lld ca=0x%04x status=0x%04x",
         (long long)prv->stat.bcas_card_id, prv->stat.ca_system_id, prv->stat.card_status);
    return 0;
}

static int get_init_status_b_cas_card(void *bcas, B_CAS_INIT_STATUS *stat) {
    B_CAS_CARD_PRIVATE_DATA *prv = private_data(bcas);
    if (prv == NULL || stat == NULL) return B_CAS_CARD_ERROR_INVALID_PARAMETER;
    if (!prv->initialized) return B_CAS_CARD_ERROR_NOT_INITIALIZED;
    memcpy(stat, &prv->stat, sizeof(*stat));
    return 0;
}

static int get_id_b_cas_card(void *bcas, B_CAS_ID *dst) {
    B_CAS_CARD_PRIVATE_DATA *prv = private_data(bcas);
    if (prv == NULL || dst == NULL) return B_CAS_CARD_ERROR_INVALID_PARAMETER;
    if (!prv->initialized) return B_CAS_CARD_ERROR_NOT_INITIALIZED;
    int n = ccid_transmit(CARD_ID_INFORMATION_ACQUIRE_CMD, (int)sizeof(CARD_ID_INFORMATION_ACQUIRE_CMD),
                          prv->rbuf, B_CAS_BUFFER_MAX);
    if (n < 19) return B_CAS_CARD_ERROR_TRANSMIT_FAILED;
    uint8_t *p = prv->rbuf + 6;
    uint8_t *tail = prv->rbuf + n;
    if (p + 1 > tail) return B_CAS_CARD_ERROR_TRANSMIT_FAILED;
    int num = p[0];
    if (num > prv->id_max) num = prv->id_max;
    p += 1;
    for (int i = 0; i < num; i++) {
        if (p + 10 > tail) return B_CAS_CARD_ERROR_TRANSMIT_FAILED;
        prv->id.data[i] = load_be_uint48(p + 2);
        p += 10;
    }
    prv->id.count = num;
    memcpy(dst, &prv->id, sizeof(*dst));
    return 0;
}

static int get_pwr_on_ctrl_b_cas_card(void *bcas, B_CAS_PWR_ON_CTRL_INFO *dst) {
    (void)bcas;
    if (dst == NULL) return B_CAS_CARD_ERROR_INVALID_PARAMETER;
    memset(dst, 0, sizeof(*dst));
    return 0;
}

static int proc_ecm_b_cas_card(void *bcas, B_CAS_ECM_RESULT *dst, uint8_t *src, int len) {
    B_CAS_CARD_PRIVATE_DATA *prv = private_data(bcas);
    if (prv == NULL || dst == NULL || src == NULL || len < 1) {
        return B_CAS_CARD_ERROR_INVALID_PARAMETER;
    }
    if (!prv->initialized) return B_CAS_CARD_ERROR_NOT_INITIALIZED;
    int slen = setup_ecm_receive_command(prv->sbuf, src, len);
    int n = -1;
    for (int retry = 0; retry < 4; retry++) {
        n = ccid_transmit(prv->sbuf, slen, prv->rbuf, B_CAS_BUFFER_MAX);
        if (n >= 25) break;
        ccid_power_on();
    }
    if (n < 25) return B_CAS_CARD_ERROR_TRANSMIT_FAILED;
    memcpy(dst->scramble_key, prv->rbuf + 6, 16);
    dst->return_code = (uint32_t)load_be_uint16(prv->rbuf + 4);
    return 0;
}

static int proc_emm_b_cas_card(void *bcas, uint8_t *src, int len) {
    B_CAS_CARD_PRIVATE_DATA *prv = private_data(bcas);
    if (prv == NULL || src == NULL || len < 1) return B_CAS_CARD_ERROR_INVALID_PARAMETER;
    if (!prv->initialized) return B_CAS_CARD_ERROR_NOT_INITIALIZED;
    int slen = setup_emm_receive_command(prv->sbuf, src, len);
    int n = ccid_transmit(prv->sbuf, slen, prv->rbuf, B_CAS_BUFFER_MAX);
    return n >= 6 ? 0 : B_CAS_CARD_ERROR_TRANSMIT_FAILED;
}
