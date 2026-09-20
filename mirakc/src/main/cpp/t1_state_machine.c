#include "t1_state_machine.h"

#include <string.h>

#define T1_I_CHAIN 0x20
#define T1_R_BLOCK 0x80
#define T1_S_BLOCK 0xC0
#define T1_S_RESPONSE 0x20
#define T1_S_RESYNCH 0x00

typedef struct {
    uint8_t bytes[T1_SM_MAX_BLOCK];
    int length;
} t1_block;

static uint8_t lrc_of(const uint8_t *data, int length) {
    uint8_t value = 0;
    for (int i = 0; i < length; ++i) value ^= data[i];
    return value;
}

static int build_block(t1_block *block, uint8_t pcb, const uint8_t *inf, int inf_length) {
    if (block == NULL || inf_length < 0 || inf_length > T1_SM_MAX_INF) return -1;
    block->bytes[0] = 0;
    block->bytes[1] = pcb;
    block->bytes[2] = (uint8_t)inf_length;
    if (inf_length > 0 && inf != NULL) memcpy(block->bytes + 3, inf, (size_t)inf_length);
    block->bytes[3 + inf_length] = lrc_of(block->bytes, 3 + inf_length);
    block->length = 4 + inf_length;
    return 0;
}

static int parse_block(const uint8_t *bytes, int length, uint8_t *pcb, const uint8_t **inf,
                       int *inf_length) {
    if (bytes == NULL || length < 4 || bytes[0] != 0) return -1;
    const int payload_length = bytes[2];
    if (payload_length > T1_SM_MAX_INF || payload_length + 4 != length) return -1;
    if (bytes[3 + payload_length] != lrc_of(bytes, 3 + payload_length)) return -1;
    if (pcb != NULL) *pcb = bytes[1];
    if (inf != NULL) *inf = bytes + 3;
    if (inf_length != NULL) *inf_length = payload_length;
    return 0;
}

static int exchange(t1_sm_exchange_fn callback, void *context, const t1_block *request,
                    uint8_t *response, int *response_length) {
    if (callback == NULL || request == NULL || response == NULL || response_length == NULL) return -1;
    const int length = callback(context, request->bytes, request->length, response, T1_SM_MAX_BLOCK);
    if (length < 0 || length > T1_SM_MAX_BLOCK) return -1;
    *response_length = length;
    return 0;
}

static int is_s_request(uint8_t pcb) {
    return (pcb & 0xC0) == T1_S_BLOCK && (pcb & T1_S_RESPONSE) == 0;
}

static int exchange_responses_to_s_request(t1_sm_exchange_fn callback, void *context,
                                           const uint8_t *request_inf, int request_inf_length,
                                           uint8_t request_pcb, uint8_t *response_pcb,
                                           const uint8_t **response_inf, int *response_inf_length,
                                           uint8_t *response_buffer) {
    t1_block request;
    if (build_block(&request, request_pcb, request_inf, request_inf_length) != 0) return -1;
    int length = 0;
    if (exchange(callback, context, &request, response_buffer, &length) != 0) return -1;
    return parse_block(response_buffer, length, response_pcb, response_inf, response_inf_length);
}

static int perform_resynch(t1_sm_exchange_fn callback, void *context, uint8_t *response_buffer) {
    uint8_t pcb = 0;
    const uint8_t *inf = NULL;
    int inf_length = 0;
    if (exchange_responses_to_s_request(callback, context, NULL, 0,
                                        T1_S_BLOCK | T1_S_RESYNCH, &pcb, &inf,
                                        &inf_length, response_buffer) != 0) {
        return -1;
    }
    if (pcb != (uint8_t)(T1_S_BLOCK | T1_S_RESPONSE | T1_S_RESYNCH) || inf_length != 0) return -1;
    return 0;
}

int t1_sm_transmit(const uint8_t *apdu, int apdu_length, uint8_t *response, int response_capacity,
                   uint8_t *send_sequence, uint8_t *receive_sequence, int ifsc,
                   t1_sm_exchange_fn callback, void *context) {
    if (apdu == NULL || apdu_length <= 0 || response == NULL || response_capacity < 0 ||
        send_sequence == NULL || receive_sequence == NULL || ifsc <= 0 || ifsc > T1_SM_MAX_INF ||
        callback == NULL) {
        return -1;
    }

    uint8_t ns = (uint8_t)(*send_sequence & 1);
    uint8_t nr = (uint8_t)(*receive_sequence & 1);
    int offset = 0;
    int response_length = 0;
    int retries = 0;
    int resynch_used = 0;
    t1_block request;
    uint8_t response_buffer[T1_SM_MAX_BLOCK];

    while (offset < apdu_length) {
        const int chunk = (apdu_length - offset > ifsc) ? ifsc : apdu_length - offset;
        const int host_chained = offset + chunk < apdu_length;
        const uint8_t pcb = (uint8_t)((ns << 6) | (host_chained ? T1_I_CHAIN : 0));
        if (build_block(&request, pcb, apdu + offset, chunk) != 0) return -1;

        int exchange_length = 0;
        if (exchange(callback, context, &request, response_buffer, &exchange_length) != 0) return -1;
        uint8_t response_pcb = 0;
        const uint8_t *response_inf = NULL;
        int response_inf_length = 0;
        if (parse_block(response_buffer, exchange_length, &response_pcb, &response_inf,
                        &response_inf_length) != 0) {
            return -1;
        }

        /* Handle card S-block requests (for example WTX) without changing T=1 state. */
        while (is_s_request(response_pcb)) {
            t1_block s_response;
            if (build_block(&s_response, (uint8_t)(response_pcb | T1_S_RESPONSE),
                            response_inf, response_inf_length) != 0 ||
                exchange(callback, context, &s_response, response_buffer, &exchange_length) != 0 ||
                parse_block(response_buffer, exchange_length, &response_pcb, &response_inf,
                            &response_inf_length) != 0) {
                return -1;
            }
        }

        if ((response_pcb & 0xC0) == T1_R_BLOCK) {
            const uint8_t response_nr = (uint8_t)((response_pcb >> 4) & 1);
            const uint8_t error = (uint8_t)(response_pcb & 0x0F);
            if (error != 0) {
                /* The failed I-block remains byte-identical and N(S) is unchanged. */
                if (response_nr != ns) return -1;
                if (retries < T1_SM_MAX_RETRIES) {
                    ++retries;
                    continue;
                }
                if (resynch_used || perform_resynch(callback, context, response_buffer) != 0) {
                    return -1;
                }
                resynch_used = 1;
                retries = 0;
                offset = 0;
                response_length = 0;
                ns = 0;
                nr = 0;
                continue;
            }
            if (response_nr != (uint8_t)(ns ^ 1) || !host_chained) return -1;
            offset += chunk;
            ns ^= 1;
            retries = 0;
            continue;
        }

        if ((response_pcb & 0x80) != 0 || host_chained ||
            ((response_pcb >> 6) & 1) != nr || (response_pcb & 0x1f) != 0 ||
            response_inf_length > response_capacity - response_length) {
            return -1;
        }
        if (response_inf_length > 0) {
            memcpy(response + response_length, response_inf, (size_t)response_inf_length);
            response_length += response_inf_length;
        }
        nr ^= 1;
        offset += chunk;
        ns ^= 1;

        while ((response_pcb & T1_I_CHAIN) != 0) {
            t1_block acknowledgement;
            if (build_block(&acknowledgement, (uint8_t)(T1_R_BLOCK | (nr << 4)), NULL, 0) != 0 ||
                exchange(callback, context, &acknowledgement, response_buffer, &exchange_length) != 0 ||
                parse_block(response_buffer, exchange_length, &response_pcb, &response_inf,
                            &response_inf_length) != 0) {
                return -1;
            }
            while (is_s_request(response_pcb)) {
                t1_block s_response;
                if (build_block(&s_response, (uint8_t)(response_pcb | T1_S_RESPONSE), response_inf,
                                response_inf_length) != 0 ||
                    exchange(callback, context, &s_response, response_buffer, &exchange_length) != 0 ||
                    parse_block(response_buffer, exchange_length, &response_pcb, &response_inf,
                                &response_inf_length) != 0) {
                    return -1;
                }
            }
            if ((response_pcb & 0x80) != 0 || (response_pcb & 0x1f) != 0 ||
                ((response_pcb >> 6) & 1) != nr ||
                response_inf_length > response_capacity - response_length) {
                return -1;
            }
            if (response_inf_length > 0) {
                memcpy(response + response_length, response_inf, (size_t)response_inf_length);
                response_length += response_inf_length;
            }
            nr ^= 1;
        }
        *send_sequence = ns;
        *receive_sequence = nr;
        return response_length;
    }
    return -1;
}
