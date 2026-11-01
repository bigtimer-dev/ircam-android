#include <stdio.h>
/*
 * Minimal UVC streaming client on top of libusb, for raw YUYV thermal frames.
 *
 * Android does not expose /dev/video* to apps, so the app opens the camera with
 * UsbManager and hands the file descriptor to libusb (libusb_wrap_sys_device).
 * This file negotiates an uncompressed stream (probe/commit), picks a bulk endpoint or
 * the smallest isochronous alternate setting that fits, reassembles payloads into frames
 * and keeps the latest complete frame for the Java side.
 */
#include "uvc.h"

#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#define TAG "ircam-uvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define CC_VIDEO 0x0E
#define SC_VIDEOCONTROL 0x01
#define SC_VIDEOSTREAMING 0x02
#define CS_INTERFACE 0x24
#define VC_HEADER 0x01
#define VS_FORMAT_UNCOMPRESSED 0x04
#define VS_FRAME_UNCOMPRESSED 0x05
#define VS_PROBE_CONTROL 0x01
#define VS_COMMIT_CONTROL 0x02
#define SET_CUR 0x01
#define GET_CUR 0x81

#define NUM_TRANSFERS 8
#define ISO_PACKETS 32

struct uvc_cam {
    libusb_context *ctx;
    libusb_device_handle *h;
    int vc_if, vs_if;
    int bcd_uvc;
    int width, height;
    int format_index, frame_index;
    uint32_t interval;
    size_t frame_size;
    uint8_t ep;
    int iso;
    int alt;
    int pkt_size;
    uint32_t payload_size;

    struct libusb_transfer *xfers[NUM_TRANSFERS];
    int active;
    volatile int running;
    pthread_t thread;
    int thread_started;

    uint8_t *assemble;
    size_t assembled;
    int fid;
    int frame_error;
    int frame_done;
    /* packet-length lag detection (see on_transfer) */
    uint32_t prev_len[ISO_PACKETS];
    int have_prev, lag_mode, votes_own, votes_prev, calib_xfers;

    pthread_mutex_t lock;
    pthread_cond_t cond;
    uint8_t *latest;
    uint64_t seq;
    uint64_t delivered;
    uint64_t dropped;
    /* diagnostics */
    uint64_t st_xfers, st_pkts, st_pkt_err, st_empty, st_bytes, st_eof, st_fidflip, st_lastsize, st_xfer_err;
    int st_last_status;
    char err[160];
};

const char *uvc_last_error(uvc_cam *c) { return c ? c->err : "no camera"; }

static void set_err(uvc_cam *c, const char *what, int r) {
    snprintf(c->err, sizeof c->err, "%s (%s)", what, r < 0 ? libusb_error_name(r) : "-");
    LOGW("%s", c->err);
}

/* ------------------------------------------------------------------ descriptors */

static int parse_descriptors(uvc_cam *c, int want_w, int want_h) {
    struct libusb_config_descriptor *cfg = NULL;
    libusb_device *dev = libusb_get_device(c->h);
    int r = libusb_get_active_config_descriptor(dev, &cfg);
    if (r < 0) { set_err(c, "read config descriptor", r); return r; }
    c->vc_if = c->vs_if = -1;
    c->format_index = c->frame_index = 0;
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
        const struct libusb_interface *itf = &cfg->interface[i];
        if (itf->num_altsetting < 1) continue;
        const struct libusb_interface_descriptor *a0 = &itf->altsetting[0];
        if (a0->bInterfaceClass != CC_VIDEO) continue;
        if (a0->bInterfaceSubClass == SC_VIDEOCONTROL && c->vc_if < 0) {
            c->vc_if = a0->bInterfaceNumber;
            const uint8_t *p = a0->extra;
            int n = a0->extra_length;
            while (n >= 3) {
                int len = p[0];
                if (len < 3 || len > n) break;
                if (p[1] == CS_INTERFACE && p[2] == VC_HEADER && len >= 5) c->bcd_uvc = p[3] | (p[4] << 8);
                p += len; n -= len;
            }
        } else if (a0->bInterfaceSubClass == SC_VIDEOSTREAMING && c->vs_if < 0) {
            c->vs_if = a0->bInterfaceNumber;
            const uint8_t *p = a0->extra;
            int n = a0->extra_length;
            int cur_fmt = 0;
            while (n >= 3) {
                int len = p[0];
                if (len < 3 || len > n) break;
                if (p[1] == CS_INTERFACE) {
                    if (p[2] == VS_FORMAT_UNCOMPRESSED && len >= 4) {
                        cur_fmt = p[3];
                    } else if (p[2] == VS_FRAME_UNCOMPRESSED && len >= 26 && cur_fmt) {
                        int fi = p[3];
                        int w = p[5] | (p[6] << 8);
                        int h = p[7] | (p[8] << 8);
                        uint32_t iv = p[21] | (p[22] << 8) | (p[23] << 16) | ((uint32_t) p[24] << 24);
                        LOGI("format %d frame %d: %dx%d interval %u", cur_fmt, fi, w, h, iv);
                        if (w == want_w && h == want_h && !c->format_index) {
                            c->format_index = cur_fmt;
                            c->frame_index = fi;
                            c->interval = iv;
                        }
                    }
                }
                p += len; n -= len;
            }
            /* endpoint: bulk on alt 0, or isochronous on a non-zero alt */
            c->ep = 0;
            c->iso = 0;
            for (int e = 0; e < a0->bNumEndpoints; e++) {
                const struct libusb_endpoint_descriptor *ep = &a0->endpoint[e];
                if ((ep->bmAttributes & 3) == LIBUSB_TRANSFER_TYPE_BULK && (ep->bEndpointAddress & 0x80)) {
                    c->ep = ep->bEndpointAddress;
                    c->pkt_size = ep->wMaxPacketSize;
                }
            }
            if (!c->ep) c->iso = 1;
        }
    }
    libusb_free_config_descriptor(cfg);
    if (c->vs_if < 0) { set_err(c, "no video streaming interface", 0); return -1; }
    if (!c->format_index) {
        snprintf(c->err, sizeof c->err, "camera has no uncompressed %dx%d mode", want_w, want_h);
        return -1;
    }
    if (!c->bcd_uvc) c->bcd_uvc = 0x0100;
    return 0;
}

/*
 * Pick the isochronous alternate setting: the smallest single-transaction endpoint that carries
 * the stream (frame bytes x fps / 8000 microframes, plus headroom), so we do not reserve a
 * high-bandwidth slot the camera never fills; otherwise the smallest one >= the payload size.
 */
static int choose_iso_alt(uvc_cam *c) {
    struct libusb_config_descriptor *cfg = NULL;
    int r = libusb_get_active_config_descriptor(libusb_get_device(c->h), &cfg);
    if (r < 0) { set_err(c, "read config descriptor", r); return r; }
    double fps = c->interval ? 1e7 / c->interval : 30.0;
    int need = (int) ((double) c->frame_size * fps / 8000.0 * 1.3) + 64;
    int single_alt = -1, single_size = 0, single_ep = 0; /* smallest mult=1 alt >= need, else largest mult=1 */
    int single_max_alt = -1, single_max = 0, single_max_ep = 0;
    int any_alt = -1, any_size = 0, any_ep = 0;          /* smallest alt >= payload size (any mult) */
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
        const struct libusb_interface *itf = &cfg->interface[i];
        for (int a = 0; a < itf->num_altsetting; a++) {
            const struct libusb_interface_descriptor *d = &itf->altsetting[a];
            if (d->bInterfaceNumber != c->vs_if) continue;
            for (int e = 0; e < d->bNumEndpoints; e++) {
                const struct libusb_endpoint_descriptor *ep = &d->endpoint[e];
                if ((ep->bmAttributes & 3) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS || !(ep->bEndpointAddress & 0x80))
                    continue;
                int mps = ep->wMaxPacketSize;
                int base = mps & 0x7FF, mult = 1 + ((mps >> 11) & 3);
                int size = base * mult;
                LOGI("alt %d: ep 0x%02x %d x %d = %d bytes", d->bAlternateSetting, ep->bEndpointAddress, mult, base, size);
                if (mult == 1) {
                    if (size >= need && (single_alt < 0 || size < single_size)) {
                        single_alt = d->bAlternateSetting; single_size = size; single_ep = ep->bEndpointAddress;
                    }
                    if (size > single_max) { single_max_alt = d->bAlternateSetting; single_max = size; single_max_ep = ep->bEndpointAddress; }
                }
                if (size >= (int) c->payload_size && (any_alt < 0 || size < any_size)) {
                    any_alt = d->bAlternateSetting; any_size = size; any_ep = ep->bEndpointAddress;
                }
            }
        }
    }
    libusb_free_config_descriptor(cfg);
    if (single_alt < 0 && single_max >= need * 10 / 13) { single_alt = single_max_alt; single_size = single_max; single_ep = single_max_ep; }
    if (single_alt >= 0) { c->alt = single_alt; c->pkt_size = single_size; c->ep = (uint8_t) single_ep; }
    else if (any_alt >= 0) { c->alt = any_alt; c->pkt_size = any_size; c->ep = (uint8_t) any_ep; }
    else { set_err(c, "no isochronous endpoint", 0); return -1; }
    LOGI("iso alt %d, packet %d bytes, ep 0x%02x (need ~%d per microframe)", c->alt, c->pkt_size, c->ep, need);
    return 0;
}

/* ------------------------------------------------------------------ probe / commit */

static int probe_len(uvc_cam *c) {
    if (c->bcd_uvc >= 0x0150) return 48;
    if (c->bcd_uvc >= 0x0110) return 34;
    return 26;
}

static int negotiate(uvc_cam *c) {
    uint8_t buf[48];
    int len = probe_len(c);
    memset(buf, 0, sizeof buf);
    buf[0] = 1; /* bmHint: keep dwFrameInterval */
    buf[2] = (uint8_t) c->format_index;
    buf[3] = (uint8_t) c->frame_index;
    memcpy(buf + 4, &c->interval, 4);
    int r = 0;
    for (int attempt = 0; attempt < 3; attempt++) {
        r = libusb_control_transfer(c->h, 0x21, SET_CUR, VS_PROBE_CONTROL << 8, c->vs_if, buf, len, 1000);
        if (r == len) break;
        if (len == 34) len = 26; else if (len == 48) len = 34; else break;
    }
    if (r < 0) { set_err(c, "UVC probe SET_CUR", r); return r; }
    uint8_t got[48];
    memset(got, 0, sizeof got);
    r = libusb_control_transfer(c->h, 0xA1, GET_CUR, VS_PROBE_CONTROL << 8, c->vs_if, got, len, 1000);
    if (r < 0) { set_err(c, "UVC probe GET_CUR", r); return r; }
    /* keep our format/frame, accept the camera's sizes */
    got[2] = (uint8_t) c->format_index;
    got[3] = (uint8_t) c->frame_index;
    uint32_t max_frame, max_payload;
    memcpy(&max_frame, got + 18, 4);
    memcpy(&max_payload, got + 22, 4);
    r = libusb_control_transfer(c->h, 0x21, SET_CUR, VS_COMMIT_CONTROL << 8, c->vs_if, got, len, 1000);
    if (r < 0) { set_err(c, "UVC commit", r); return r; }
    c->payload_size = max_payload ? max_payload : 3072;
    LOGI("negotiated: bcdUVC %04x, max frame %u, max payload %u", c->bcd_uvc, max_frame, max_payload);
    return 0;
}

/* ------------------------------------------------------------------ payloads */

static void finish_frame(uvc_cam *c) {
    c->st_lastsize = c->assembled;
    if (c->assembled == c->frame_size && !c->frame_error) {
        pthread_mutex_lock(&c->lock);
        memcpy(c->latest, c->assemble, c->frame_size);
        c->seq++;
        pthread_cond_broadcast(&c->cond);
        pthread_mutex_unlock(&c->lock);
    } else if (c->assembled) {
        c->dropped++;
    }
    c->assembled = 0;
    c->frame_error = 0;
}

/*
 * Frames end at the payload with the EOF bit. The FID bit is not used for framing: the
 * InfiRay P2 Pro toggles it about 20 KB after a new frame has already started, while its
 * EOF bit marks the 196608-byte boundary correctly. Overflow without EOF resynchronises.
 */
static void handle_payload(uvc_cam *c, const uint8_t *p, int n) {
    if (n < 2) return;
    int hl = p[0];
    if (hl < 2 || hl > n) return;
    int info = p[1];
    int fid = info & 1;
    if (c->fid >= 0 && fid != c->fid) c->st_fidflip++;
    c->fid = fid;
    if (info & 0x40) c->frame_error = 1;
    int data = n - hl;
    if (data > 0) {
        c->st_bytes += data;
        if (c->assembled + data <= c->frame_size) {
            memcpy(c->assemble + c->assembled, p + hl, data);
            c->assembled += data;
        } else {
            c->frame_error = 1;
        }
    }
    if (info & 0x02) { c->st_eof++; finish_frame(c); }
}

/*
 * Some phone kernels (seen on the MediaTek MT6765 Galaxy A04) return each isochronous transfer
 * with the packet lengths of the transfer completed before it, while the data is correct. The
 * buffer past a packet's real end is left zeroed, so for the first transfers we check whether
 * the data ends where this transfer's lengths say or where the previous one's do, and then use
 * whichever matches.
 */
static int data_end(const uint8_t *p, int size) {
    int run = 0;
    for (int j = 12; j < size; j++) {
        if (p[j] == 0) { if (++run == 8) return j - 7; } else run = 0;
    }
    return size;
}

static void calibrate_lag(uvc_cam *c, struct libusb_transfer *t, int np) {
    if (c->have_prev) {
        for (int i = 0; i < np; i++) {
            unsigned own = t->iso_packet_desc[i].actual_length, prev = c->prev_len[i];
            if (own == prev || own < 16 || prev < 16 || own > (unsigned) c->pkt_size - 8 || prev > (unsigned) c->pkt_size - 8) continue;
            int end = data_end(libusb_get_iso_packet_buffer_simple(t, i), c->pkt_size);
            if (end == (int) own) c->votes_own++;
            else if (end == (int) prev) c->votes_prev++;
        }
    }
    int total = c->votes_own + c->votes_prev;
    if (total >= 64 || ++c->calib_xfers >= 64) {
        c->lag_mode = c->votes_prev > c->votes_own * 4 ? 1 : 0;
        LOGI("packet lengths: %s (votes own %d, previous %d)",
             c->lag_mode ? "lagging one transfer, compensating" : "normal", c->votes_own, c->votes_prev);
    }
}

static void LIBUSB_CALL on_transfer(struct libusb_transfer *t) {
    uvc_cam *c = t->user_data;
    c->st_xfers++;
    if (t->status != LIBUSB_TRANSFER_COMPLETED) { c->st_xfer_err++; c->st_last_status = t->status; }
    if (t->status == LIBUSB_TRANSFER_COMPLETED) {
        if (t->type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
            int np = t->num_iso_packets < ISO_PACKETS ? t->num_iso_packets : ISO_PACKETS;
            if (c->lag_mode < 0) calibrate_lag(c, t, np);
            for (int i = 0; i < np; i++) {
                struct libusb_iso_packet_descriptor *pd = &t->iso_packet_desc[i];
                c->st_pkts++;
                if (pd->status != LIBUSB_TRANSFER_COMPLETED) { c->st_pkt_err++; c->st_last_status = 100 + pd->status; continue; }
                unsigned len = c->lag_mode == 1 ? c->prev_len[i] : pd->actual_length;
                if (c->lag_mode < 0) continue; /* still deciding which lengths are right */
                if (!len) { c->st_empty++; continue; }
                if (len > (unsigned) c->pkt_size) len = (unsigned) c->pkt_size;
                handle_payload(c, libusb_get_iso_packet_buffer_simple(t, i), (int) len);
            }
            for (int i = 0; i < np; i++) c->prev_len[i] = t->iso_packet_desc[i].actual_length;
            c->have_prev = 1;
        } else if (t->actual_length > 0) {
            /* a bulk transfer holds one payload (dwMaxPayloadTransferSize bytes max) */
            handle_payload(c, t->buffer, t->actual_length);
        }
    }
    if (c->running && (t->status == LIBUSB_TRANSFER_COMPLETED || t->status == LIBUSB_TRANSFER_TIMED_OUT)) {
        memset(t->buffer, 0xEE, t->length);
        if (libusb_submit_transfer(t) == 0) return;
    }
    if (t->status == LIBUSB_TRANSFER_NO_DEVICE) c->running = 0;
    c->active--;
}

static void *event_thread(void *arg) {
    uvc_cam *c = arg;
    struct timeval tv = {0, 100000};
    struct timespec last, now;
    clock_gettime(CLOCK_MONOTONIC, &last);
    while (c->running || c->active > 0) {
        libusb_handle_events_timeout_completed(c->ctx, &tv, NULL);
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (now.tv_sec - last.tv_sec >= 10) {
            last = now;
            LOGI("stats: xfers %llu (err %llu) pkts %llu (err %llu, empty %llu) bytes %llu eof %llu fidflip %llu frames %llu dropped %llu lastsize %llu laststatus %d active %d",
                 (unsigned long long) c->st_xfers, (unsigned long long) c->st_xfer_err, (unsigned long long) c->st_pkts,
                 (unsigned long long) c->st_pkt_err, (unsigned long long) c->st_empty, (unsigned long long) c->st_bytes,
                 (unsigned long long) c->st_eof, (unsigned long long) c->st_fidflip, (unsigned long long) c->seq,
                 (unsigned long long) c->dropped, (unsigned long long) c->st_lastsize, c->st_last_status, c->active);
        }
    }
    pthread_mutex_lock(&c->lock);
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->lock);
    return NULL;
}

/* ------------------------------------------------------------------ public API */

uvc_cam *uvc_open(int fd, int width, int height, char *err, size_t errlen) {
    uvc_cam *c = calloc(1, sizeof *c);
    if (!c) return NULL;
    pthread_mutex_init(&c->lock, NULL);
    pthread_cond_init(&c->cond, NULL);
    c->fid = -1;
    c->width = width;
    c->height = height;
    c->frame_size = (size_t) width * height * 2;
    int r;
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
    if ((r = libusb_init(&c->ctx)) < 0) { set_err(c, "libusb_init", r); goto fail; }
    if ((r = libusb_wrap_sys_device(c->ctx, (intptr_t) fd, &c->h)) < 0) { set_err(c, "libusb_wrap_sys_device", r); goto fail; }
    if (parse_descriptors(c, width, height) < 0) goto fail;
    if (c->vc_if >= 0) {
        libusb_detach_kernel_driver(c->h, c->vc_if);
        libusb_claim_interface(c->h, c->vc_if);
    }
    libusb_detach_kernel_driver(c->h, c->vs_if);
    if ((r = libusb_claim_interface(c->h, c->vs_if)) < 0) { set_err(c, "claim streaming interface", r); goto fail; }
    c->assemble = malloc(c->frame_size);
    c->latest = malloc(c->frame_size);
    if (!c->assemble || !c->latest) { set_err(c, "out of memory", 0); goto fail; }
    return c;
fail:
    if (err) snprintf(err, errlen, "%s", c->err);
    uvc_close(c);
    return NULL;
}

int uvc_start(uvc_cam *c) {
    int r;
    libusb_set_interface_alt_setting(c->h, c->vs_if, 0);
    if ((r = negotiate(c)) < 0) return r;
    if (c->iso) {
        if ((r = choose_iso_alt(c)) < 0) return r;
        if ((r = libusb_set_interface_alt_setting(c->h, c->vs_if, c->alt)) < 0) { set_err(c, "set alt setting", r); return r; }
    }
    c->running = 1;
    c->assembled = 0;
    c->fid = -1;
    c->lag_mode = c->iso ? -1 : 0;
    c->have_prev = c->votes_own = c->votes_prev = c->calib_xfers = 0;
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        struct libusb_transfer *t;
        if (c->iso) {
            int len = c->pkt_size * ISO_PACKETS;
            t = libusb_alloc_transfer(ISO_PACKETS);
            uint8_t *buf = malloc(len);
            libusb_fill_iso_transfer(t, c->h, c->ep, buf, len, ISO_PACKETS, on_transfer, c, 1000);
            libusb_set_iso_packet_lengths(t, c->pkt_size);
        } else {
            int len = (int) c->payload_size;
            t = libusb_alloc_transfer(0);
            uint8_t *buf = malloc(len);
            libusb_fill_bulk_transfer(t, c->h, c->ep, buf, len, on_transfer, c, 1000);
        }
        t->flags = LIBUSB_TRANSFER_FREE_BUFFER;
        c->xfers[i] = t;
        if ((r = libusb_submit_transfer(t)) == 0) c->active++;
        else set_err(c, "submit transfer", r);
    }
    if (!c->active) { c->running = 0; return -1; }
    if (pthread_create(&c->thread, NULL, event_thread, c) == 0) c->thread_started = 1;
    LOGI("streaming %dx%d via %s endpoint 0x%02x", c->width, c->height, c->iso ? "iso" : "bulk", c->ep);
    return 0;
}

int uvc_read(uvc_cam *c, uint8_t *out, size_t len, int timeout_ms) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeout_ms / 1000;
    ts.tv_nsec += (long) (timeout_ms % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&c->lock);
    while (c->seq == c->delivered && c->running) {
        if (pthread_cond_timedwait(&c->cond, &c->lock, &ts) == ETIMEDOUT) break;
    }
    int got = 0;
    if (c->seq != c->delivered) {
        size_t n = len < c->frame_size ? len : c->frame_size;
        memcpy(out, c->latest, n);
        c->delivered = c->seq;
        got = (int) n;
    } else if (!c->running) {
        got = -1; /* stream ended (device gone) */
    }
    pthread_mutex_unlock(&c->lock);
    return got;
}

void uvc_stop(uvc_cam *c) {
    if (!c || !c->running && !c->thread_started) return;
    c->running = 0;
    for (int i = 0; i < NUM_TRANSFERS; i++)
        if (c->xfers[i]) libusb_cancel_transfer(c->xfers[i]);
    if (c->thread_started) {
        pthread_join(c->thread, NULL);
        c->thread_started = 0;
    }
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (c->xfers[i]) libusb_free_transfer(c->xfers[i]);
        c->xfers[i] = NULL;
    }
    c->active = 0;
    if (c->h) libusb_set_interface_alt_setting(c->h, c->vs_if, 0);
}

void uvc_close(uvc_cam *c) {
    if (!c) return;
    uvc_stop(c);
    if (c->h) {
        if (c->vs_if >= 0) libusb_release_interface(c->h, c->vs_if);
        if (c->vc_if >= 0) libusb_release_interface(c->h, c->vc_if);
        libusb_close(c->h);
    }
    if (c->ctx) libusb_exit(c->ctx);
    free(c->assemble);
    free(c->latest);
    pthread_mutex_destroy(&c->lock);
    pthread_cond_destroy(&c->cond);
    free(c);
}

long uvc_dropped(uvc_cam *c) { return c ? (long) c->dropped : 0; }
