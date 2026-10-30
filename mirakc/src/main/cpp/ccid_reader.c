#include "ccid_reader.h"
#include "t1_state_machine.h"

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "b25-ccid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
#define CCID_TRANSFER_METADATA_LOG_LIMIT 32u

/* USB descriptor fields used to locate the CCID bulk channel */
#define USB_DT_DEVICE 0x01
#define USB_DT_CONFIG 0x02
#define USB_DT_INTERFACE 0x04
#define USB_DT_ENDPOINT 0x05
#define USB_DT_CCID_CLASS 0x21
#define USB_CLASS_CCID 0x0B
#define USB_ENDPOINT_DIR_IN 0x80
#define USB_ENDPOINT_XFER_MASK 0x03
#define USB_ENDPOINT_XFER_BULK 0x02

#define T1_MAX_INF 254

static int g_fd = -1;
static int g_ep_out = -1; /* bulk OUT endpoint address, from the descriptor */
static int g_ep_in = -1;  /* bulk IN endpoint address, from the descriptor */
static int g_interface = -1; /* bInterfaceNumber of the selected CCID interface */
static uint8_t g_slot;    /* CCID bSlot we address; slot 0 always exists */
static uint8_t g_seq;
static int g_tpdu = 1;    /* 1 = wrap APDUs in T=1 blocks ourselves */
static uint8_t g_ns;      /* our T=1 send sequence number */
static uint8_t g_nr;      /* next T=1 receive sequence number expected */
static int g_ifsc = 32;   /* max INF the card accepts, from GetParameters */
static unsigned int g_transfer_metadata_log_count;
static int g_transfer_metadata_log_limit_notice;

static int should_log_transfer_metadata(void) {
    if (g_transfer_metadata_log_count < CCID_TRANSFER_METADATA_LOG_LIMIT) {
        ++g_transfer_metadata_log_count;
        return 1;
    }
    if (!g_transfer_metadata_log_limit_notice) {
        g_transfer_metadata_log_limit_notice = 1;
        LOGI("CCID transfer metadata log limit reached; suppressing further per-transfer logs");
    }
    return 0;
}

static void put_le32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static uint32_t get_le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
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
    if (g_ep_out < 0) return -1;
    uint8_t seq = g_seq++;
    buf[0] = type;
    put_le32(buf + 1, len);
    buf[5] = g_slot;
    buf[6] = seq;
    buf[7] = spec0;
    buf[8] = spec1;
    buf[9] = spec2;
    if (len > 0 && data != NULL) memcpy(buf + 10, data, len);
    if (should_log_transfer_metadata()) {
        LOGI("OUT type=0x%02x len=%u seq=%u", type, len, seq);
    }
    if (bulk((uint8_t)g_ep_out, buf, (int)(10 + len)) != (int)(10 + len)) return -1;
    return seq;
}

static int recv_ccid(uint8_t expect_type, int seq, uint8_t *data, int max, uint8_t *extra) {
    uint8_t buf[10 + 2048];
    if (g_ep_in < 0) return -1;
    for (int attempt = 0; attempt < 8; attempt++) {
        int n = bulk((uint8_t)g_ep_in, buf, (int)sizeof(buf));
        if (n < 10) {
            LOGE("CCID IN truncated n=%d errno=%d", n, errno);
            return -1;
        }
        uint32_t dlen = get_le32(buf + 1);
        while ((uint32_t)(n - 10) < dlen && n < (int)sizeof(buf)) {
            int m = bulk((uint8_t)g_ep_in, buf + n, (int)sizeof(buf) - n);
            if (m <= 0) break;
            n += m;
        }
        uint8_t status = buf[7];
        uint8_t error = buf[8];
        uint8_t icc = status & 0x03;
        uint8_t cmd = (status >> 6) & 0x03;
        if (should_log_transfer_metadata()) {
            LOGI("IN  type=0x%02x len=%u slot=%u seq=%u status=0x%02x(icc=%u cmd=%u) err=0x%02x chain=0x%02x n=%d",
                 buf[0], dlen, buf[5], buf[6], status, icc, cmd, error, buf[9], n);
        }
        if (buf[5] != g_slot) {
            LOGE("bSlot mismatch got=%u want=%u, discarding", buf[5], g_slot);
            continue;
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

/* Parse state for one interface while walking the descriptor blob. */
typedef struct {
    int number;       /* bInterfaceNumber, -1 before any interface descriptor */
    int alt;          /* bAlternateSetting, -1 before any interface descriptor */
    int is_ccid;      /* bInterfaceClass == USB_CLASS_CCID */
    int ambiguous;    /* duplicate bulk endpoint in one direction */
    int ep_out;       /* bulk OUT endpoint address on this interface, -1 if none */
    int ep_in;        /* bulk IN endpoint address on this interface, -1 if none */
    int has_ccid;     /* a full CCID class descriptor was seen on this interface */
    uint8_t max_slot; /* bMaxSlotIndex from the CCID class descriptor */
    uint32_t protocols;
    uint32_t max_ifsd;
    uint32_t features;
    uint32_t max_msg;
} ccid_candidate;

static void reset_candidate(ccid_candidate *candidate) {
    memset(candidate, 0, sizeof(*candidate));
    candidate->number = -1;
    candidate->alt = -1;
    candidate->ep_out = -1;
    candidate->ep_in = -1;
}

/*
 * Supported layout restriction: the reader must present its CCID channel on
 * alternate setting 0 of the single device configuration.  The Android side
 * claims CCID interfaces but never issues setInterface/setConfiguration, so
 * only alternate 0 of the kernel-activated configuration can actually carry
 * USBDEVFS_BULK transfers; anything else is rejected rather than guessed.
 * A usable candidate also needs both bulk directions and a CCID class
 * descriptor (the exchange level cannot be derived without it).
 */
static int candidate_usable(const ccid_candidate *candidate) {
    return candidate->is_ccid && candidate->alt == 0 && !candidate->ambiguous &&
           candidate->ep_out >= 0 && candidate->ep_in >= 0 && candidate->has_ccid;
}

static void adopt_candidate(const ccid_candidate *candidate, int *usable_count, int *adopted,
                            ccid_candidate *chosen) {
    if (!candidate_usable(candidate)) {
        if (candidate->is_ccid && candidate->alt == 0) {
            LOGI("skipping CCID interface %d: %s", candidate->number,
                 candidate->ambiguous ? "ambiguous duplicate bulk endpoint"
                 : !candidate->has_ccid ? "no CCID class descriptor"
                                        : "missing bulk endpoint");
        }
        return;
    }
    ++*usable_count;
    if (!*adopted) {
        *chosen = *candidate;
        *adopted = 1;
    }
}

/*
 * Selects the CCID interface and derives the bulk endpoints and slot domain
 * from the device descriptor blob.  Returns 0 after filling g_ep_out,
 * g_ep_in, g_interface and g_tpdu, or -1 when the blob is malformed, has an
 * unsupported layout, or exposes no usable CCID interface (explicit
 * rejection instead of fixed endpoints).
 *
 * The blob must look like a real USBFS descriptor read: exactly one device
 * descriptor first (full length, advertising exactly one configuration),
 * then the single complete configuration tree.  Interface, class and
 * endpoint descriptors are only owned inside that configuration tree, so a
 * tree outside it (or a second configuration hiding past the bounded read)
 * can never lend endpoints to the selection.
 */
static int select_ccid_interface(void) {
    uint8_t d[1024];
    ssize_t n = pread(g_fd, d, sizeof(d), 0);
    if (n <= 0) {
        LOGE("descriptor pread failed n=%zd errno=%d", n, errno);
        return -1;
    }
    ccid_candidate current;
    ccid_candidate chosen;
    reset_candidate(&current);
    reset_candidate(&chosen);
    int usable_count = 0;
    int adopted = 0;
    int have_device = 0;
    int in_config = 0;
    int have_interface = 0;
    int config_count = 0;
    ssize_t config_start = -1;
    uint32_t config_total = 0;
    ssize_t i = 0;
    while (i + 1 < n) {
        const int len = d[i];
        const int type = d[i + 1];
        if (len < 2 || i + len > n) {
            LOGE("malformed descriptor tail at offset %zd len=%d type=0x%02x", i, len, type);
            return -1;
        }
        if (type == USB_DT_DEVICE) {
            if (have_device) {
                LOGE("duplicate device descriptor at offset %zd", i);
                return -1;
            }
            if (i != 0) {
                LOGE("device descriptor is not the blob prefix (offset %zd)", i);
                return -1;
            }
            if (len < 18) {
                LOGE("undersized device descriptor len=%d", len);
                return -1;
            }
            if (d[i + 17] != 1) {
                LOGE("unsupported: device advertises %u configurations", d[i + 17]);
                return -1;
            }
            have_device = 1;
        } else if (type == USB_DT_CONFIG) {
            if (!have_device) {
                LOGE("configuration descriptor before device descriptor");
                return -1;
            }
            if (len < 9) {
                LOGE("undersized configuration descriptor len=%d", len);
                return -1;
            }
            ++config_count;
            if (config_count > 1) {
                LOGE("unsupported: multiple USB configurations in descriptor blob");
                return -1;
            }
            /* Descriptor ownership restarts at the configuration boundary. */
            in_config = 1;
            have_interface = 0;
            reset_candidate(&current);
            config_start = i;
            config_total = (uint32_t)d[i + 2] | ((uint32_t)d[i + 3] << 8);
        } else if (type == USB_DT_INTERFACE) {
            if (len < 9) {
                LOGE("undersized interface descriptor len=%d", len);
                return -1;
            }
            if (!in_config) {
                LOGE("interface descriptor before any configuration");
                return -1;
            }
            adopt_candidate(&current, &usable_count, &adopted, &chosen);
            reset_candidate(&current);
            have_interface = 1;
            current.number = d[i + 2];
            current.alt = d[i + 3];
            current.is_ccid = (d[i + 5] == USB_CLASS_CCID);
        } else if (type == USB_DT_CCID_CLASS) {
            if (!in_config) {
                LOGE("class descriptor before any configuration");
                return -1;
            }
            /* 0x21 is class-specific; only a CCID interface owns it here. */
            if (current.is_ccid && current.alt == 0) {
                if (len < 54) {
                    LOGE("undersized CCID class descriptor len=%d", len);
                    return -1;
                }
                if (!current.has_ccid) {
                    const uint8_t *c = d + i;
                    current.has_ccid = 1;
                    current.max_slot = c[4];
                    current.protocols = get_le32(c + 6);
                    current.max_ifsd = get_le32(c + 28);
                    current.features = get_le32(c + 40);
                    current.max_msg = get_le32(c + 44);
                }
            } else if (!have_interface) {
                LOGE("orphaned class descriptor before any interface");
                return -1;
            }
        } else if (type == USB_DT_ENDPOINT) {
            if (len < 7) {
                LOGE("undersized endpoint descriptor len=%d", len);
                return -1;
            }
            if (!in_config) {
                LOGE("endpoint descriptor before any configuration");
                return -1;
            }
            if (!have_interface) {
                LOGE("orphaned endpoint descriptor before any interface");
                return -1;
            }
            if (current.is_ccid && current.alt == 0 &&
                (d[i + 3] & USB_ENDPOINT_XFER_MASK) == USB_ENDPOINT_XFER_BULK) {
                const uint8_t address = d[i + 2];
                if ((address & USB_ENDPOINT_DIR_IN) != 0) {
                    if (current.ep_in >= 0) {
                        current.ambiguous = 1;
                    } else {
                        current.ep_in = address;
                    }
                } else {
                    if (current.ep_out >= 0) {
                        current.ambiguous = 1;
                    } else {
                        current.ep_out = address;
                    }
                }
            }
        }
        i += len;
    }
    if (i != n) {
        LOGE("descriptor walk stopped at %zd of %zd bytes", i, n);
        return -1;
    }
    if (!have_device) {
        LOGE("no device descriptor prefix in blob");
        return -1;
    }
    if (config_count == 0) {
        LOGE("no configuration descriptor in blob");
        return -1;
    }
    if (config_start < 0 || config_total != (uint32_t)(n - config_start)) {
        LOGE("configuration wTotalLength=%u inconsistent with descriptor blob (%zd bytes "
             "from its start)",
             config_total, config_start >= 0 ? n - config_start : n);
        return -1;
    }
    adopt_candidate(&current, &usable_count, &adopted, &chosen);
    if (!adopted) {
        LOGE("no usable CCID interface "
             "(class 0x0B, alternate 0, bulk OUT/IN, CCID class descriptor)");
        return -1;
    }
    if (usable_count > 1) {
        LOGI("descriptor exposes %d usable CCID interfaces; using interface %d", usable_count,
             chosen.number);
    }
    if (g_slot > chosen.max_slot) {
        LOGE("CCID slot %u outside descriptor domain 0..%u", g_slot, chosen.max_slot);
        return -1;
    }
    g_ep_out = chosen.ep_out;
    g_ep_in = chosen.ep_in;
    g_interface = chosen.number;
    uint32_t level = chosen.features & CCID_LEVEL_MASK;
    LOGI("CCID desc dwProtocols=0x%08x dwMaxIFSD=%u dwFeatures=0x%08x dwMaxCCIDMessageLength=%u",
         chosen.protocols, chosen.max_ifsd, chosen.features, chosen.max_msg);
    g_tpdu = (level == CCID_LEVEL_TPDU) || (level == 0);
    LOGI("exchange level=0x%08x -> %s", level,
         g_tpdu ? "TPDU (we drive T=1)" : "APDU (reader drives T=1)");
    return 0;
}

int ccid_open(int usb_fd) {
    g_fd = usb_fd;
    g_seq = 0;
    g_ns = 0;
    g_nr = 0;
    g_tpdu = 1;
    g_ifsc = 32;
    g_ep_out = -1;
    g_ep_in = -1;
    g_interface = -1;
    g_slot = 0;
    g_transfer_metadata_log_count = 0;
    g_transfer_metadata_log_limit_notice = 0;
    /*
     * The Android service claims every CCID interface before duplicating this
     * USBFS fd for the filter child.  Do not issue CLAIMINTERFACE again here:
     * on Android USBFS this is the same open file description and a second
     * claim can block while the Java-side owner is still active.  The Java
     * SianoReaderHandle owns the claim and releases it after this child exits.
     */
    if (select_ccid_interface() != 0) {
        g_fd = -1;
        return -1;
    }
    LOGI("CCID using Android-claimed interface %d fd=%d ep_out=0x%02x ep_in=0x%02x slot=%u",
         g_interface, usb_fd, g_ep_out, g_ep_in, g_slot);
    return 0;
}

void ccid_close(void) {
    /* The Android-side UsbDeviceConnection owns the interface claim. */
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

static int t1_exchange_callback(void *context, const uint8_t *request, int request_len,
                                uint8_t *response, int response_capacity) {
    (void)context;
    return ccid_xfr_block(request, request_len, response, response_capacity);
}

/* Carries one APDU over T=1, including bounded R-block recovery. */
static int t1_transmit(const uint8_t *apdu, int apdu_len, uint8_t *resp, int resp_max) {
    return t1_sm_transmit(apdu, apdu_len, resp, resp_max, &g_ns, &g_nr, g_ifsc,
                          t1_exchange_callback, NULL);
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

    g_ns = 0;
    g_nr = 0;
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
