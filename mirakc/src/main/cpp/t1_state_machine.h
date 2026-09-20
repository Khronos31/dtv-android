#ifndef T1_STATE_MACHINE_H
#define T1_STATE_MACHINE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define T1_SM_MAX_INF 254
#define T1_SM_MAX_BLOCK 258
#define T1_SM_MAX_RETRIES 3

typedef int (*t1_sm_exchange_fn)(void *context, const uint8_t *request, int request_len,
                                 uint8_t *response, int response_capacity);

/*
 * Transmit one APDU over T=1.  Sequence state is committed only on success;
 * the caller can therefore power-cycle/reconnect after a failed exchange.
 */
int t1_sm_transmit(const uint8_t *apdu, int apdu_len, uint8_t *response, int response_capacity,
                   uint8_t *send_sequence, uint8_t *receive_sequence, int ifsc,
                   t1_sm_exchange_fn exchange, void *context);

#ifdef __cplusplus
}
#endif

#endif
