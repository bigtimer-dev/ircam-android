#pragma once
#include <stddef.h>
#include <stdint.h>
#include "libusb/libusb.h"

typedef struct uvc_cam uvc_cam;

uvc_cam *uvc_open(int fd, int width, int height, char *err, size_t errlen);
int uvc_start(uvc_cam *c);
/* Copies the newest complete frame. Returns bytes copied, 0 on timeout, -1 if the stream ended. */
int uvc_read(uvc_cam *c, uint8_t *out, size_t len, int timeout_ms);
void uvc_stop(uvc_cam *c);
void uvc_close(uvc_cam *c);
const char *uvc_last_error(uvc_cam *c);
long uvc_dropped(uvc_cam *c);
