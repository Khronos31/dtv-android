#ifndef CCID_READER_H
#define CCID_READER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int ccid_open(int usb_fd);
void ccid_close(void);
int ccid_power_on(void);
int ccid_transmit(const uint8_t *apdu, int apdu_len, uint8_t *resp, int resp_max);

#ifdef __cplusplus
}
#endif

#endif
