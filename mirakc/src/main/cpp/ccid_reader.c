#include "ccid_reader.h"

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "b25-ccid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CCID_OUT 0x01
#define CCID_IN 0x82
#define TIMEOUT_MS 8000

/* CCID message types */
#define PC_TO_RDR_ICC_POWER_ON 0x62
#define PC_TO_RDR_ICC_POWER_OFF 0x63
#define PC_TO_RDR_GET_PARAMETERS 0x6C
#define PC_TO_RDR_XFR_BLOCK 0x6F
#define RDR_TO_PC_DATA_BLOCK 0x80
#define RDR_TO_PC_SLOT_STATUS 0x81
#define RDR_TO_PC_PARAMETERS 0x82

/* dwFeatures exchange level (CCID 1.1 section 5.1) */
#define CCID_LEVEL_MASK 0x00070000u
#define CCID_LEVEL_TPDU 0x00010000u

#define T1_MAX_INF 254

static int g_fd = -1;
static uint8_t g_seq;
static int g_tpdu = 1;    /* 1 = wrap APDUs in T=1 blocks ourselves */
static uint8_t g_ns;      /* our T=1 send sequence number */
static int g_ifsc = 32;   /* max INF the card accepts, from GetParameters */
static int g_verbose;     /* full hex tracing budget, spent during init */

static void put_le32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static uint32_t get_le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void hexlog(const char *what, const uint8_t *p, int n) {
    static const char digits[] = "0123456789abcdef";
    if (n <= 0) {
        LOGI("%s (empty)", what);
        return;
    }
    for (int off = 0; off < n; off += 32) {
        char line[32 * 3 + 1];
        int m = n - off > 32 ? 32 : n - off;
        for (int i = 0; i < m; i++) {
            line[i * 3] = digits[p[off + i] >> 4];
            line[i * 3 + 1] = digits[p[off + i] & 0x0f];
            line[i * 3 + 2] = ' ';
        }
        line[m * 3] = '\0';
        LOGI("%s +%03d %s", what, off, line);
    }
}

static int bulk(uint8_t ep, void *data, int len) {
    struct usbdevfs_bulktransfer xfer;
    memset(&xfer, 0, sizeof(xfer));
    xfer.ep = ep;
    xfer.len = (unsigned int)len;
    xfer.timeout = TIMEOUT_MS;
    xfer.data = data;
    int n = ioctl(g_fd, USBDEVFS_BULK, &xfer);
    if (n < 0) {
        LOGE("USBDEVFS_BULK ep=0x%02x len=%d errno=%d", ep, len, errno);
    }
    return n;
}

/* Returns the bSeq stamped on the message, or -1. */
static int send_ccid(uint8_t type, const uint8_t *data, uint32_t len, uint8_t spec0, uint8_t spec1,
                     uint8_t spec2) {
    uint8_t buf[10 + 1024];
    if (len > sizeof(buf) - 10) return -1;
    uint8_t seq = g_seq++;
    buf[0] = type;
    put_le32(buf + 1, len);
    buf[5] = 0;
    buf[6] = seq;
    buf[7] = spec0;
    buf[8] = spec1;
    buf[9] = spec2;
    if (len > 0 && data != NULL) memcpy(buf + 10, data, len);
    if (g_verbose > 0) {
        LOGI("OUT type=0x%02x len=%u seq=%u spec=%02x %02x %02x", type, len, seq, spec0, spec1, spec2);
        hexlog("OUT", buf, (int)(10 + len));
    }
    if (bulk(CCID_OUT, buf, (int)(10 + len)) != (int)(10 + len)) return -1;
    return seq;
}

static int recv_ccid(uint8_t expect_type, int seq, uint8_t *data, int max, uint8_t *extra) {
    uint8_t buf[10 + 2048];
    for (int attempt = 0; attempt < 8; attempt++) {
        int n = bulk(CCID_IN, buf, (int)sizeof(buf));
        if (n < 10) {
            LOGE("CCID IN truncated n=%d errno=%d", n, errno);
            return -1;
        }
        uint32_t dlen = get_le32(buf + 1);
        while ((uint32_t)(n - 10) < dlen && n < (int)sizeof(buf)) {
            int m = bulk(CCID_IN, buf + n, (int)sizeof(buf) - n);
            if (m <= 0) break;
            n += m;
        }
        uint8_t status = buf[7];
        uint8_t error = buf[8];
        uint8_t icc = status & 0x03;
        uint8_t cmd = (status >> 6) & 0x03;
        if (g_verbose > 0) {
            g_verbose--;
            LOGI("IN  type=0x%02x len=%u slot=%u seq=%u status=0x%02x(icc=%u cmd=%u) err=0x%02x chain=0x%02x n=%d",
                 buf[0], dlen, buf[5], buf[6], status, icc, cmd, error, buf[9], n);
            hexlog("IN ", buf, n);
        }
        if (seq >= 0 && buf[6] != (uint8_t)seq) {
            LOGE("bSeq mismatch got=%u want=%d, discarding", buf[6], seq);
            continue;
        }
        if (cmd == 2) {
            LOGI("time extension requested (bwi=%u), waiting for the real reply", error);
            continue;
        }
        if (cmd == 1) {
            LOGE("CCID command failed type=0x%02x status=0x%02x error=0x%02x icc=%u", buf[0], status,
                 error, icc);
            return -1;
        }
        if (buf[0] != expect_type) {
            LOGE("unexpected CCID msg 0x%02x (wanted 0x%02x) status=0x%02x", buf[0], expect_type, status);
            return -1;
        }
        if (extra) *extra = buf[9];
        if (dlen > (uint32_t)(n - 10)) dlen = (uint32_t)(n - 10);
        if ((int)dlen > max) dlen = (uint32_t)max;
        if (data && dlen) memcpy(data, buf + 10, dlen);
        return (int)dlen;
    }
    LOGE("CCID IN gave up after repeated mismatches");
    return -1;
}

static int ccid_xfr_block(const uint8_t *data, int len, uint8_t *resp, int resp_max) {
    int seq = send_ccid(PC_TO_RDR_XFR_BLOCK, data, (uint32_t)len, 0, 0, 0);
    if (seq < 0) return -1;
    return recv_ccid(RDR_TO_PC_DATA_BLOCK, seq, resp, resp_max, NULL);
}

static void read_descriptors(void) {
    uint8_t d[1024];
    ssize_t n = pread(g_fd, d, sizeof(d), 0);
    if (n <= 0) {
        LOGE("descriptor pread failed n=%zd errno=%d; assuming TPDU level", n, errno);
        return;
    }
    hexlog("desc", d, (int)n);
    for (ssize_t i = 0; i + 1 < n;) {
        int len = d[i];
        if (len < 2 || i + len > n) break;
        if (d[i + 1] == 0x21 && len >= 54) {
            const uint8_t *c = d + i;
            uint32_t protocols = get_le32(c + 6);
            uint32_t max_ifsd = get_le32(c + 28);
            uint32_t features = get_le32(c + 40);
            uint32_t max_msg = get_le32(c + 44);
            uint32_t level = features & CCID_LEVEL_MASK;
            LOGI("CCID desc dwProtocols=0x%08x dwMaxIFSD=%u dwFeatures=0x%08x dwMaxCCIDMessageLength=%u",
                 protocols, max_ifsd, features, max_msg);
            g_tpdu = (level == CCID_LEVEL_TPDU) || (level == 0);
            LOGI("exchange level=0x%08x -> %s", level, g_tpdu ? "TPDU (we drive T=1)" : "APDU (reader drives T=1)");
            return;
        }
        i += len;
    }
    LOGE("no CCID class descriptor found; assuming TPDU level");
}

int ccid_open(int usb_fd) {
    g_fd = usb_fd;
    g_seq = 0;
    g_ns = 0;
    g_tpdu = 1;
    g_ifsc = 32;
    g_verbose = 60;
    unsigned int iface = 0;
    if (ioctl(g_fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0 && errno != EBUSY) {
        LOGE("CLAIMINTERFACE failed errno=%d", errno);
        return -1;
    }
    LOGI("CCID claimed interface 0 fd=%d", usb_fd);
    read_descriptors();
    return 0;
}

void ccid_close(void) {
    if (g_fd >= 0) {
        unsigned int iface = 0;
        ioctl(g_fd, USBDEVFS_RELEASEINTERFACE, &iface);
    }
    g_fd = -1;
}

static uint8_t lrc_of(const uint8_t *p, int n) {
    uint8_t x = 0;
    for (int i = 0; i < n; i++) x ^= p[i];
    return x;
}

/* One T=1 block out, one T=1 block in. Returns the INF length received. */
static int t1_exchange(uint8_t pcb, const uint8_t *inf, int ilen, uint8_t *rpcb, uint8_t *rinf,
                       int rmax) {
    uint8_t blk[4 + T1_MAX_INF];
    uint8_t rbuf[16 + T1_MAX_INF];
    if (ilen < 0 || ilen > T1_MAX_INF) return -1;
    blk[0] = 0x00;
    blk[1] = pcb;
    blk[2] = (uint8_t)ilen;
    if (ilen > 0 && inf != NULL) memcpy(blk + 3, inf, (size_t)ilen);
    blk[3 + ilen] = lrc_of(blk, 3 + ilen);
    int n = ccid_xfr_block(blk, 4 + ilen, rbuf, (int)sizeof(rbuf));
    if (n < 4) {
        LOGE("T=1 reply too short n=%d (sent pcb=0x%02x len=%d)", n, pcb, ilen);
        return -1;
    }
    int rlen = rbuf[2];
    if (rlen + 4 > n) {
        LOGE("T=1 LEN=%d does not fit in n=%d", rlen, n);
        return -1;
    }
    if (lrc_of(rbuf, 3 + rlen) != rbuf[3 + rlen]) {
        LOGE("T=1 LRC mismatch pcb=0x%02x len=%d", rbuf[1], rlen);
        return -1;
    }
    if (rpcb) *rpcb = rbuf[1];
    if (rlen > rmax) rlen = rmax;
    if (rinf && rlen > 0) memcpy(rinf, rbuf + 3, (size_t)rlen);
    return rlen;
}

static int t1_negotiate_ifsd(int ifsd) {
    uint8_t inf = (uint8_t)ifsd;
    uint8_t rpcb = 0;
    uint8_t rinf[8];
    int n = t1_exchange(0xC1, &inf, 1, &rpcb, rinf, (int)sizeof(rinf));
    if (n < 1 || rpcb != 0xE1) {
        LOGE("IFSD negotiation failed n=%d pcb=0x%02x", n, rpcb);
        return -1;
    }
    LOGI("IFSD negotiated = %u", rinf[0]);
    return 0;
}

/* Carries one APDU over T=1, handling chaining in both directions plus S-block requests. */
static int t1_transmit(const uint8_t *apdu, int apdu_len, uint8_t *resp, int resp_max) {
    uint8_t sbuf[T1_MAX_INF];
    uint8_t rinf[T1_MAX_INF];
    uint8_t rpcb = 0;
    int sent = 0;
    int out = 0;

    int chunk = apdu_len > g_ifsc ? g_ifsc : apdu_len;
    uint8_t pcb = (uint8_t)((g_ns << 6) | (chunk < apdu_len ? 0x20 : 0x00));
    const uint8_t *payload = apdu;
    int payload_len = chunk;

    for (int guard = 0; guard < 64; guard++) {
        int rlen = t1_exchange(pcb, payload, payload_len, &rpcb, rinf, (int)sizeof(rinf));
        if (rlen < 0) return -1;
        if ((pcb & 0x80) == 0) {
            g_ns ^= 1;
            sent += payload_len;
        }

        if ((rpcb & 0x80) == 0) {
            /* I-block: response data from the card */
            if (out + rlen > resp_max) {
                LOGE("response overflows caller buffer (%d + %d > %d)", out, rlen, resp_max);
                return -1;
            }
            memcpy(resp + out, rinf, (size_t)rlen);
            out += rlen;
            if ((rpcb & 0x20) == 0) return out;
            /* card is chaining: acknowledge with an R-block */
            pcb = (uint8_t)(0x80 | ((((rpcb >> 6) & 1) ^ 1) << 4));
            payload = NULL;
            payload_len = 0;
            continue;
        }

        if ((rpcb & 0xC0) == 0x80) {
            /* R-block */
            if (rpcb & 0x0F) {
                LOGE("card reported a transmission error, R-block pcb=0x%02x", rpcb);
                return -1;
            }
            if (sent >= apdu_len) {
                LOGE("unexpected R-block 0x%02x after the final I-block", rpcb);
                return -1;
            }
            int c = apdu_len - sent;
            int more = c > g_ifsc;
            if (more) c = g_ifsc;
            pcb = (uint8_t)((g_ns << 6) | (more ? 0x20 : 0x00));
            payload = apdu + sent;
            payload_len = c;
            continue;
        }

        /* S-block. Requests (bit 0x20 clear) get the matching response echoed back. */
        if (rpcb & 0x20) {
            LOGE("unsolicited S-block response pcb=0x%02x", rpcb);
            return -1;
        }
        LOGI("S-block request pcb=0x%02x len=%d, answering", rpcb, rlen);
        if (rlen > (int)sizeof(sbuf)) rlen = (int)sizeof(sbuf);
        if (rlen > 0) memcpy(sbuf, rinf, (size_t)rlen);
        pcb = (uint8_t)(rpcb | 0x20);
        payload = sbuf;
        payload_len = rlen;
    }
    LOGE("T=1 exchange did not converge");
    return -1;
}

static void read_parameters(void) {
    uint8_t param[16];
    int seq = send_ccid(PC_TO_RDR_GET_PARAMETERS, NULL, 0, 0, 0, 0);
    if (seq < 0) return;
    uint8_t proto = 0xff;
    int n = recv_ccid(RDR_TO_PC_PARAMETERS, seq, param, (int)sizeof(param), &proto);
    if (n < 0) {
        LOGE("GetParameters failed");
        return;
    }
    if (proto == 1 && n >= 7) {
        LOGI("T=1 params TCCKST1=0x%02x GT=%u WI=0x%02x IFSC=%u NAD=0x%02x", param[1], param[2],
             param[3], param[5], param[6]);
        if (param[5] >= 16) g_ifsc = param[5];
    } else {
        LOGI("GetParameters bProtocolNum=%u n=%d", proto, n);
    }
}

int ccid_power_on(void) {
    uint8_t atr[256];
    int seq = send_ccid(PC_TO_RDR_ICC_POWER_OFF, NULL, 0, 0, 0, 0);
    if (seq >= 0) recv_ccid(RDR_TO_PC_SLOT_STATUS, seq, NULL, 0, NULL);
    usleep(200000);

    seq = send_ccid(PC_TO_RDR_ICC_POWER_ON, NULL, 0, 0, 0, 0);
    if (seq < 0) return -1;
    int n = recv_ccid(RDR_TO_PC_DATA_BLOCK, seq, atr, (int)sizeof(atr), NULL);
    if (n < 2) {
        LOGE("IccPowerOn failed n=%d", n);
        return -1;
    }
    LOGI("ATR %d bytes", n);
    hexlog("ATR", atr, n);

    g_ns = 0;
    g_ifsc = 32;
    read_parameters();
    if (g_tpdu && t1_negotiate_ifsd(T1_MAX_INF) != 0) {
        LOGE("continuing without IFSD negotiation (IFSD stays at the default 32)");
    }
    LOGI("card ready, IFSC=%d mode=%s", g_ifsc, g_tpdu ? "TPDU" : "APDU");
    return 0;
}

int ccid_transmit(const uint8_t *apdu, int apdu_len, uint8_t *resp, int resp_max) {
    if (g_tpdu) return t1_transmit(apdu, apdu_len, resp, resp_max);
    return ccid_xfr_block(apdu, apdu_len, resp, resp_max);
}
